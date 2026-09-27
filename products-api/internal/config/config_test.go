package config

import (
	"log/slog"
	"testing"
	"time"
)

func env(values map[string]string) func(string) string {
	return func(k string) string { return values[k] }
}

func TestLoadDefaults(t *testing.T) {
	cfg, err := Load(env(nil))
	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if cfg.Port != "8080" || cfg.RequestTimeout != 5*time.Second || cfg.ShutdownTimeout != 10*time.Second {
		t.Fatalf("valores por defecto inesperados: %+v", cfg)
	}
	if cfg.FaultInjectionEnabled {
		t.Fatal("la inyección de fallos debe estar deshabilitada por defecto")
	}
	if cfg.LogLevel != slog.LevelInfo {
		t.Fatalf("nivel de log = %v", cfg.LogLevel)
	}
}

func TestLoadOverrides(t *testing.T) {
	cfg, err := Load(env(map[string]string{
		"PORT": "9090", "REQUEST_TIMEOUT": "2s", "SHUTDOWN_TIMEOUT": "500ms",
		"FAULT_INJECTION_ENABLED": "true", "LOG_LEVEL": "debug",
	}))
	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if cfg.Port != "9090" || cfg.RequestTimeout != 2*time.Second ||
		cfg.ShutdownTimeout != 500*time.Millisecond || !cfg.FaultInjectionEnabled || cfg.LogLevel != slog.LevelDebug {
		t.Fatalf("configuración inesperada: %+v", cfg)
	}
}

func TestLoadRejectsInvalidValues(t *testing.T) {
	invalid := []map[string]string{
		{"PORT": "abc"},
		{"PORT": "70000"},
		{"REQUEST_TIMEOUT": "5"},
		{"REQUEST_TIMEOUT": "-1s"},
		{"SHUTDOWN_TIMEOUT": "0s"},
		{"FAULT_INJECTION_ENABLED": "yes please"},
		{"LOG_LEVEL": "verbose"},
	}
	for _, values := range invalid {
		if _, err := Load(env(values)); err == nil {
			t.Errorf("se esperaba error para %v", values)
		}
	}
}
