package main

import (
	"runtime"
	"syscall"
)

// disableQuickEdit turns off QuickEdit in the console window. With QuickEdit on, a single click
// into the window selects text and pauses the program until Enter — and the stream stopped.
func disableQuickEdit() {
	const (
		enableQuickEditMode = 0x0040
		enableExtendedFlags = 0x0080
	)
	h, err := syscall.GetStdHandle(syscall.STD_INPUT_HANDLE)
	if err != nil {
		return
	}
	var mode uint32
	if syscall.GetConsoleMode(h, &mode) != nil {
		return
	}
	setConsoleMode := syscall.NewLazyDLL("kernel32.dll").NewProc("SetConsoleMode")
	setConsoleMode.Call(uintptr(h), uintptr(mode&^enableQuickEditMode|enableExtendedFlags))
}

// keepAwake stops Windows from sleeping (and turning the screen off) while the server runs,
// like caffeinate on the Mac. The setting belongs to the calling thread, so that thread is kept alive.
func keepAwake() {
	const (
		esContinuous      = 0x80000000
		esSystemRequired  = 0x00000001
		esDisplayRequired = 0x00000002
	)
	go func() {
		runtime.LockOSThread()
		syscall.NewLazyDLL("kernel32.dll").NewProc("SetThreadExecutionState").
			Call(uintptr(esContinuous | esSystemRequired | esDisplayRequired))
		select {}
	}()
}
