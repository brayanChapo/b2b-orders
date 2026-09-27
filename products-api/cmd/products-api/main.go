package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"b2b-orders/products-api/internal/catalog"
	"b2b-orders/products-api/internal/config"
	"b2b-orders/products-api/internal/httpapi"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "-healthcheck" {
		os.Exit(healthcheck())
	}
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "products-api:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load(os.Getenv)
	if err != nil {
		return err
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel})).
		With("service", "products-api")
	slog.SetDefault(logger)

	products, err := catalog.NewSeeded()
	if err != nil {
		return fmt.Errorf("datos semilla inválidos: %w", err)
	}
	health := httpapi.NewHealth()

	srv := &http.Server{
		Addr: ":" + cfg.Port,
		Handler: httpapi.NewHandler(httpapi.Options{
			Catalog:               products,
			Health:                health,
			Logger:                logger,
			RequestTimeout:        cfg.RequestTimeout,
			FaultInjectionEnabled: cfg.FaultInjectionEnabled,
		}),
		// Límites contra clientes lentos o conexiones colgadas.
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      cfg.RequestTimeout + 5*time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	serveErr := make(chan error, 1)
	go func() {
		logger.Info("servidor iniciado",
			"port", cfg.Port,
			"listings", products.Len(),
			"fault_injection", cfg.FaultInjectionEnabled)
		serveErr <- srv.ListenAndServe()
	}()

	select {
	case err := <-serveErr:
		return fmt.Errorf("servidor detenido: %w", err)
	case <-ctx.Done():
	}

	logger.Info("apagado iniciado", "timeout", cfg.ShutdownTimeout.String())
	health.MarkShuttingDown()
	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("apagado incompleto: %w", err)
	}
	if err := <-serveErr; !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	logger.Info("apagado completo")
	return nil
}

func healthcheck() int {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	client := http.Client{Timeout: 2 * time.Second}
	resp, err := client.Get("http://127.0.0.1:" + port + "/health")
	if err != nil {
		return 1
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 1
	}
	return 0
}
