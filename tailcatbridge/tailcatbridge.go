package tailcatbridge

import (
    "context"
    "fmt"
    "io"
    "os"
    "time"

    "github.com/tailscale/tailcat"
    "tailscale.com/types/logger"
)

type Progress interface {
    OnProgress(sent int64, total int64)
}

func SendFile(addr string, path string, progress Progress) error {
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

    ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
    defer cancel()
    if err := pingUntil(ctx, cl); err != nil {
        return err
    }
    conn, err := cl.DialTCPPort(ctx, 1)
    if err != nil {
        return err
    }
    defer conn.Close()

    buf := make([]byte, 64*1024)
    var sent int64
    total := st.Size()
    for {
        n, readErr := f.Read(buf)
        if n > 0 {
            wn, writeErr := conn.Write(buf[:n])
            sent += int64(wn)
            if progress != nil {
                progress.OnProgress(sent, total)
            }
            if writeErr != nil {
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
    if cw, ok := conn.(interface{ CloseWrite() error }); ok {
        _ = cw.CloseWrite()
    }
    _, _ = io.Copy(io.Discard, conn)
    if progress != nil {
        progress.OnProgress(total, total)
    }
    return nil
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
