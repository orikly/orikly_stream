package main

import "syscall"

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
