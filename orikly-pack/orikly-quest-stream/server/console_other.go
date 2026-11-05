//go:build !windows

package main

func disableQuickEdit() {}

func keepAwake() {} // macOS: caffeinate in main.go
