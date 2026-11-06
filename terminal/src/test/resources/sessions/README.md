Real terminal sessions replayed by `RealSessionTest`.

- Recorded with tmux 3.8 on macOS: the program in a tmux pane with `TERM=xterm-256color` and `LANG=ko_KR.UTF-8`
- `<name>.raw`: raw output captured with `pipe-pane -o`
- `<name>.screen`: tmux's rendering of the final screen (`capture-pane -p`)
- `<name>.meta`: `cursor_y cursor_x width height alternate_on`
- The test replays the raw bytes into `TerminalEmulator` and expects tmux's screen text, cursor position and alternate-screen state
