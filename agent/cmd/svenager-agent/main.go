package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/klackwerk/svenager/agent/internal/agent"
	"github.com/klackwerk/svenager/agent/internal/api"
	"github.com/klackwerk/svenager/agent/internal/config"
)

// version is set at build time via -ldflags "-X main.version=..."
var version = "dev"

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}

	switch os.Args[1] {
	case "version":
		fmt.Println("svenager-agent", version)
	case "status":
		fs := flag.NewFlagSet("status", flag.ExitOnError)
		configPath := fs.String("config", config.DefaultPath, "path to the agent configuration file")
		_ = fs.Parse(os.Args[2:])
		cfg, err := config.Load(*configPath)
		if err != nil {
			fmt.Println("enrolled:  no")
			fmt.Fprintln(os.Stderr, "reason:", err)
			os.Exit(1)
		}
		fmt.Println("enrolled:  yes")
		fmt.Println("server:   ", cfg.ServerURL)
		fmt.Println("device id:", cfg.DeviceID)
		fmt.Println("state dir:", cfg.StateDir)
	case "enroll":
		fs := flag.NewFlagSet("enroll", flag.ExitOnError)
		server := fs.String("server", "", "Svenager server base URL, e.g. https://svenager.example.org")
		token := fs.String("token", "", "enrollment token issued by the server")
		configPath := fs.String("config", config.DefaultPath, "path to the agent configuration file")
		_ = fs.Parse(os.Args[2:])
		if *server == "" || *token == "" {
			fmt.Fprintln(os.Stderr, "error: --server and --token are required")
			fs.Usage()
			os.Exit(2)
		}
		if err := agent.Enroll(*server, *token, *configPath); err != nil {
			fmt.Fprintln(os.Stderr, "enrollment failed:", err)
			os.Exit(1)
		}
		fmt.Println("enrollment successful")
	case "run":
		fs := flag.NewFlagSet("run", flag.ExitOnError)
		configPath := fs.String("config", config.DefaultPath, "path to the agent configuration file")
		server := fs.String("server", "", "Svenager server URL — enables automatic token-less registration when the device is not enrolled yet (an admin approves it in the UI)")
		_ = fs.Parse(os.Args[2:])
		cfg, err := config.Load(*configPath)
		if err != nil && *server != "" {
			ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
			err = agent.RegisterAndWait(ctx, *server, *configPath, 30*time.Second)
			stop()
			if err != nil {
				fmt.Fprintln(os.Stderr, "registration failed:", err)
				os.Exit(1)
			}
			cfg, err = config.Load(*configPath)
		}
		if err != nil {
			fmt.Fprintln(os.Stderr, "failed to load configuration:", err)
			os.Exit(1)
		}
		if err := agent.Run(cfg, api.NewClient(cfg, version)); err != nil {
			fmt.Fprintln(os.Stderr, "agent stopped with error:", err)
			os.Exit(1)
		}
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintf(os.Stderr, `svenager-agent — Svenager device agent

Usage:
  svenager-agent enroll --server URL --token TOKEN [--config PATH]
  svenager-agent run [--config PATH] [--server URL]
      --server enables token-less enrollment for pre-configured images:
      the agent polls the server until an admin approves the device.
  svenager-agent status [--config PATH]
  svenager-agent version
`)
}
