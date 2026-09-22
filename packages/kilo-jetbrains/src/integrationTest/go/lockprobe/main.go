// lockprobe is the Go side of the JVM↔Go writer.lock interop check (design doc §7.2/§14.1:
// "JVM writer 与 Go consumer 同时持锁/救援 — 不能用两套各自通过的锁单测替代").
//
// It attempts an exclusive byte-range lock over [0,1) of the given file via LockFileEx —
// the same Windows primitive the JVM's FileChannel.tryLock maps to — and reports whether
// the range was acquirable, releasing it before exit so the probe never disturbs the peer.
//
// Usage: go run main.go <path-to-writer.lock>
//
// Prints ACQUIRED (exit 0) when the range is free, or HELD_BY_PEER (exit 2) when another
// process (the live JVM writer) holds it. Kept stdlib-only and CGO-free to match the
// cs-cloud release matrix.
package main

import (
	"fmt"
	"os"
	"syscall"
	"unsafe"
)

const (
	lockFileFailImmediately = 0x00000001
	lockFileExclusiveLock   = 0x00000002
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: lockprobe <lock-file>")
		os.Exit(64)
	}
	file, err := os.OpenFile(os.Args[1], os.O_RDWR, 0)
	if err != nil {
		fmt.Fprintln(os.Stderr, "open:", err)
		os.Exit(64)
	}
	defer file.Close()

	kernel32 := syscall.NewLazyDLL("kernel32.dll")
	lockFileEx := kernel32.NewProc("LockFileEx")
	unlockFileEx := kernel32.NewProc("UnlockFileEx")

	var overlapped syscall.Overlapped
	ret, _, callErr := lockFileEx.Call(
		file.Fd(),
		lockFileFailImmediately|lockFileExclusiveLock,
		0, // reserved
		1, // nNumberOfBytesToLockLow — byte range [0,1), mirroring the JVM writer
		0, // nNumberOfBytesToLockHigh
		uintptr(unsafe.Pointer(&overlapped)),
	)
	if ret == 0 {
		fmt.Println("HELD_BY_PEER")
		fmt.Fprintln(os.Stderr, "LockFileEx:", callErr)
		os.Exit(2)
	}
	unlockFileEx.Call(file.Fd(), 0, 1, 0, uintptr(unsafe.Pointer(&overlapped)))
	fmt.Println("ACQUIRED")
}
