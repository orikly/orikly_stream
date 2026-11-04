package main

import (
	"io"
)

// asyncLog writes log lines to the console from its own goroutine.
// If the console window stops reading (on Windows a click into the window used to pause it),
// headsets and viewers keep working; lines are dropped instead of blocking the stream.
type asyncLog chan []byte

func newAsyncLog(out io.Writer) asyncLog {
	ch := make(asyncLog, 256)
	go func() {
		for b := range ch {
			out.Write(b)
		}
	}()
	return ch
}

func (a asyncLog) Write(p []byte) (int, error) {
	b := append([]byte(nil), p...)
	select {
	case a <- b:
	default: // console is stuck: drop the line
	}
	return len(p), nil
}
