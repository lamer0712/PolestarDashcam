package tailcatbridge

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/tailscale/tailcat"
	"tailscale.com/types/logger"
)

type Progress interface {
	OnProgress(sent int64, total int64)
}

type Cancellation interface {
	IsCancelled() bool
}

type AddressCallback interface {
	OnAddress(addr string)
	OnError(message string)
}

var controlMu sync.Mutex
var controlServer *tailcat.Server
var controlListener net.Listener

func StartAddressExchange(callback AddressCallback) (addr string, err error) {
	defer func() {
		if r := recover(); r != nil {
			StopAddressExchange()
			err = fmt.Errorf("tailcat address exchange panic: %v", r)
		}
	}()
	StopAddressExchange()

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()

	srv := &tailcat.Server{Logf: logger.Discard}
	ln, err := srv.Listen(ctx, "tcp", ":2")
	if err != nil {
		srv.Close()
		return "", err
	}

	controlMu.Lock()
	controlServer = srv
	controlListener = ln
	controlMu.Unlock()

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				if callback != nil && !strings.Contains(err.Error(), "closed") {
					callback.OnError(err.Error())
				}
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_ = c.SetDeadline(time.Now().Add(20 * time.Second))
				b, err := io.ReadAll(io.LimitReader(c, 4096))
				if err != nil {
					if callback != nil {
						callback.OnError(err.Error())
					}
					return
				}
				addr := strings.TrimSpace(string(b))
				if !strings.HasPrefix(addr, "tc") {
					if callback != nil {
						callback.OnError("invalid Tailcat address")
					}
					return
				}
				if callback != nil {
					callback.OnAddress(addr)
				}
			}(conn)
		}
	}()

	return string(srv.TailcatAddr()), nil
}

func StopAddressExchange() {
	defer func() { _ = recover() }()
	controlMu.Lock()
	ln := controlListener
	srv := controlServer
	controlListener = nil
	controlServer = nil
	controlMu.Unlock()
	if ln != nil {
		_ = ln.Close()
	}
	if srv != nil {
		_ = srv.Close()
	}
}

func SendFile(addr string, path string, progress Progress) error {
	return SendFileCancelable(addr, path, progress, nil)
}

func SendFileCancelable(addr string, path string, progress Progress, cancellation Cancellation) error {
	if addr == "" {
		return fmt.Errorf("tailcat address is required")
	}
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil {
		return err
	}

	cl := tailcat.NewClient(tailcat.Addr(addr))
	cl.Logf = logger.Discard
	defer cl.Close()

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stopDone := watchCancellation(ctx, cancel, cancellation)
	defer stopDone()

	connectCtx, connectCancel := context.WithTimeout(ctx, 60*time.Second)
	defer connectCancel()
	if err := checkCancelled(cancellation); err != nil {
		return err
	}
	if err := pingUntil(connectCtx, cl); err != nil {
		if isCancelled(cancellation) {
			return fmt.Errorf("operation cancelled")
		}
		return err
	}
	if err := checkCancelled(cancellation); err != nil {
		return err
	}
	conn, err := cl.DialTCPPort(connectCtx, 1)
	if err != nil {
		if isCancelled(cancellation) {
			return fmt.Errorf("operation cancelled")
		}
		return err
	}
	defer conn.Close()
	return sendFileContents(conn, f, st.Size(), progress, cancellation)
}

func sendFileContents(conn net.Conn, f *os.File, total int64, progress Progress, cancellation Cancellation) error {
	reader := bufio.NewReaderSize(conn, 4096)
	_ = conn.SetReadDeadline(time.Now().Add(45 * time.Second))
	request, err := reader.ReadString('\n')
	if err != nil {
		if isCancelled(cancellation) {
			return fmt.Errorf("operation cancelled")
		}
		return fmt.Errorf("reading resume request: %w", err)
	}
	const requestPrefix = "GALLERYPLUS/1 RESUME "
	if !strings.HasPrefix(request, requestPrefix) {
		return fmt.Errorf("unsupported receiver protocol")
	}
	requestedOffset, err := strconv.ParseInt(strings.TrimSpace(strings.TrimPrefix(request, requestPrefix)), 10, 64)
	if err != nil || requestedOffset < 0 {
		return fmt.Errorf("invalid resume offset")
	}
	resumeOffset := requestedOffset
	if resumeOffset > total {
		resumeOffset = total
	}
	if _, err := f.Seek(resumeOffset, io.SeekStart); err != nil {
		return fmt.Errorf("seeking source file: %w", err)
	}
	_ = conn.SetReadDeadline(time.Time{})
	_ = conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
	if _, err := io.WriteString(conn, fmt.Sprintf("GALLERYPLUS/1 FILE %d %d\n", total, resumeOffset)); err != nil {
		return fmt.Errorf("writing file header: %w", err)
	}

	buf := make([]byte, 64*1024)
	sent := resumeOffset
	if progress != nil {
		progress.OnProgress(sent, total)
	}
	for {
		if err := checkCancelled(cancellation); err != nil {
			return err
		}
		n, readErr := f.Read(buf)
		if n > 0 {
			_ = conn.SetWriteDeadline(time.Now().Add(30 * time.Second))
			wn, writeErr := conn.Write(buf[:n])
			sent += int64(wn)
			if progress != nil {
				progress.OnProgress(sent, total)
			}
			if writeErr != nil {
				if isCancelled(cancellation) {
					return fmt.Errorf("operation cancelled")
				}
				return writeErr
			}
			if wn != n {
				return io.ErrShortWrite
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			return readErr
		}
	}
	if err := checkCancelled(cancellation); err != nil {
		return err
	}
	_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
	if cw, ok := conn.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
	_ = conn.SetReadDeadline(time.Now().Add(60 * time.Second))
	ack, err := reader.ReadString('\n')
	if err != nil {
		return fmt.Errorf("waiting for receiver confirmation: %w", err)
	}
	wantAck := fmt.Sprintf("GALLERYPLUS/1 OK %d", total)
	if strings.TrimSpace(ack) != wantAck {
		return fmt.Errorf("receiver did not confirm the complete file")
	}
	if progress != nil {
		progress.OnProgress(total, total)
	}
	return nil
}

func checkCancelled(c Cancellation) error {
	if isCancelled(c) {
		return fmt.Errorf("operation cancelled")
	}
	return nil
}

func isCancelled(c Cancellation) bool {
	return c != nil && c.IsCancelled()
}

func watchCancellation(ctx context.Context, cancel context.CancelFunc, c Cancellation) func() {
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(100 * time.Millisecond)
		defer ticker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-done:
				return
			case <-ticker.C:
				if isCancelled(c) {
					cancel()
					return
				}
			}
		}
	}()
	return func() { close(done) }
}

func pingUntil(ctx context.Context, cl *tailcat.Client) error {
	for {
		pctx, cancel := context.WithTimeout(ctx, 5*time.Second)
		_, err := cl.Ping(pctx)
		cancel()
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return fmt.Errorf("ping: %w", err)
		}
		time.Sleep(250 * time.Millisecond)
	}
}
