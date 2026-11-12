package main

// H.264 test clips (made with ffmpeg: testsrc2, Baseline 3.1, key frame every 2 s, SPS/PPS before every
// key frame, AUD between frames) — sent like a headset with app 0.5.0+.

import (
	"bytes"
	_ "embed"
)

//go:embed clip360.h264
var clip360 []byte

//go:embed clip720.h264
var clip720 []byte

type h264Frame struct {
	key  bool
	data []byte
}

var frames360, frames720 []h264Frame

// splitAU cuts an Annex B stream into frames at the access unit delimiters (NAL type 9).
func splitAU(stream []byte) []h264Frame {
	var out []h264Frame
	var starts []int
	for i := 0; i+4 < len(stream); i++ {
		if stream[i] == 0 && stream[i+1] == 0 && stream[i+2] == 1 && stream[i+3]&0x1f == 9 {
			s := i
			if s > 0 && stream[s-1] == 0 {
				s-- // 4-byte start code
			}
			starts = append(starts, s)
		}
	}
	starts = append(starts, len(stream))
	for k := 0; k+1 < len(starts); k++ {
		au := stream[starts[k]:starts[k+1]]
		out = append(out, h264Frame{key: hasIDR(au), data: au})
	}
	return out
}

func hasIDR(au []byte) bool {
	for i := 0; i+3 < len(au); i++ {
		if au[i] == 0 && au[i+1] == 0 && au[i+2] == 1 && au[i+3]&0x1f == 5 {
			return true
		}
	}
	return false
}

func init() {
	frames360 = splitAU(clip360)
	frames720 = splitAU(clip720)
	_ = bytes.MinRead
}
