package config

import (
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Port                  string
	RequestTimeout        time.Duration
	ShutdownTimeout       time.Duration
	FaultInjectionEnabled bool
	LogLevel              slog.Level
}

func Load(getenv func(string) string) (Config, error) {
	cfg := Config{
		Port:            "8080",
		RequestTimeout:  5 * time.Second,
		ShutdownTimeout: 10 * time.Second,
		LogLevel:        slog.LevelInfo,
	}
	var err error

	if v := getenv("PORT"); v != "" {
		if n, convErr := strconv.Atoi(v); convErr != nil || n < 1 || n > 65535 {
			return Config{}, fmt.Errorf("PORT inválido: %q", v)
		}
		cfg.Port = v
	}
	if cfg.RequestTimeout, err = duration(getenv, "REQUEST_TIMEOUT", cfg.RequestTimeout); err != nil {
		return Config{}, err
	}
	if cfg.ShutdownTimeout, err = duration(getenv, "SHUTDOWN_TIMEOUT", cfg.ShutdownTimeout); err != nil {
		return Config{}, err
	}
	if v := getenv("FAULT_INJECTION_ENABLED"); v != "" {
		if cfg.FaultInjectionEnabled, err = strconv.ParseBool(v); err != nil {
			return Config{}, fmt.Errorf("FAULT_INJECTION_ENABLED inválido: %q", v)
		}
	}
	if v := getenv("LOG_LEVEL"); v != "" {
		if err := cfg.LogLevel.UnmarshalText([]byte(strings.ToUpper(v))); err != nil {
			return Config{}, fmt.Errorf("LOG_LEVEL inválido: %q", v)
		}
	}
	return cfg, nil
}

func duration(getenv func(string) string, name string, def time.Duration) (time.Duration, error) {
	v := getenv(name)
	if v == "" {
		return def, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil || d <= 0 {
		return 0, fmt.Errorf("%s inválido: %q (ejemplo: 5s, 500ms)", name, v)
	}
	return d, nil
}
