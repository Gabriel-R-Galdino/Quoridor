package client.gui;

import common.GameStateDTO;
import common.WallOrientation;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.ExecutionException;

/** Estado de apresentação e componentes pertencem à EDT; os workers cuidam do RMI. */
final class GameWindow extends JFrame implements GuiCallback.Listener, BoardPanel.Actions {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private final JTextField name = new JTextField(22);
    private final JTextField host = new JTextField("localhost", 22);
    private final JButton connect = new JButton("Conectar à partida");
    private final JLabel connectionStatus = new JLabel("Informe seu nome e o host do Registry.");
    private final JLabel turn = new JLabel("Aguardando jogadores");
    private final JLabel identity = new JLabel(" ");
    private final JLabel[] players = new JLabel[4];
    private final JTextArea log = new JTextArea(12, 24);
    private final JToggleButton wallMode = new JToggleButton("Modo parede");
    private final JButton rotate = new JButton("Orientação: horizontal");
    private final BoardPanel board = new BoardPanel(this);
    private GuiSession session;
    private GameStateDTO state;
    private int playerId = -1;
    private boolean connecting;
    private boolean busy;
    private boolean closing;
    private Long pendingVersion;
    private Integer announcedWinner;
    private WallOrientation orientation = WallOrientation.HORIZONTAL;

    GameWindow() {
        super("Quoridor • Cliente Swing");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        root.add(connectionPanel(), "connect"); root.add(gamePanel(), "game");
        setContentPane(root); setSize(1060, 780); setMinimumSize(new Dimension(860, 680)); setLocationRelativeTo(null);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { leave(); }
        });
        connect.addActionListener(event -> connect());
        name.addActionListener(event -> connect());
        host.addActionListener(event -> connect());
        wallMode.addActionListener(event -> { board.wallMode(wallMode.isSelected()); refresh(); });
        rotate.addActionListener(event -> {
            orientation = orientation == WallOrientation.HORIZONTAL ? WallOrientation.VERTICAL : WallOrientation.HORIZONTAL;
            rotate.setText("Orientação: " + (orientation == WallOrientation.HORIZONTAL ? "horizontal" : "vertical"));
            board.orientation(orientation);
        });
        refresh();
    }

    private JPanel connectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(new Color(239, 242, 247));
        JPanel form = new JPanel(); form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new EmptyBorder(35, 40, 35, 40));
        JLabel title = new JLabel("QUORIDOR"); title.setFont(title.getFont().deriveFont(Font.BOLD, 30));
        form.add(title); form.add(new JLabel("Quatro jogadores · Java RMI")); form.add(Box.createVerticalStrut(28));
        form.add(new JLabel("Nome do jogador")); form.add(name); form.add(Box.createVerticalStrut(14));
        form.add(new JLabel("Host do Registry (porta 1099)")); form.add(host); form.add(Box.createVerticalStrut(22));
        form.add(connect); form.add(Box.createVerticalStrut(16)); form.add(connectionStatus);
        panel.add(form); return panel;
    }

    private JPanel gamePanel() {
        JPanel panel = new JPanel(new BorderLayout(14, 10)); panel.setBorder(new EmptyBorder(16, 16, 16, 16));
        JPanel header = new JPanel(new BorderLayout());
        turn.setFont(turn.getFont().deriveFont(Font.BOLD, 20)); header.add(turn, BorderLayout.CENTER); header.add(identity, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH); panel.add(board, BorderLayout.CENTER);
        JPanel side = new JPanel(); side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setPreferredSize(new Dimension(295, 600));
        for (int id = 0; id < 4; id++) {
            players[id] = new JLabel("Jogador " + (char) ('A' + id));
            players[id].setForeground(BoardPanel.COLORS[id]); players[id].setOpaque(true);
            players[id].setBorder(new EmptyBorder(12, 10, 12, 10));
            players[id].setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
            side.add(players[id]); side.add(Box.createVerticalStrut(6));
        }
        wallMode.setAlignmentX(Component.LEFT_ALIGNMENT); rotate.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(Box.createVerticalStrut(12)); side.add(wallMode); side.add(Box.createVerticalStrut(8)); side.add(rotate);
        JLabel help = new JLabel("<html><br>Clique em uma casa destacada ou use<br>as setas. Diagonais: clique no tabuleiro.<br><br>Parede: passe o mouse entre casas.<br>Verde: possível · Vermelho: bloqueada.<br></html>");
        side.add(help); side.add(Box.createVerticalStrut(14)); side.add(new JLabel("Mensagens da partida"));
        log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(log); scroll.setAlignmentX(Component.LEFT_ALIGNMENT); side.add(scroll);
        JButton exit = new JButton("Sair da partida"); exit.addActionListener(event -> leave());
        side.add(Box.createVerticalStrut(10)); side.add(exit); panel.add(side, BorderLayout.EAST); return panel;
    }

    private void connect() {
        if (connecting || closing) { return; }
        String playerName = name.getText().strip(), registryHost = host.getText().strip();
        if (playerName.isEmpty() || registryHost.isEmpty()) { connectionStatus.setText("Preencha o nome e o host."); return; }
        connecting = true; connect.setEnabled(false); name.setEnabled(false); host.setEnabled(false);
        connectionStatus.setText("Conectando…");
        new SwingWorker<GuiSession, Void>() {
            @Override protected GuiSession doInBackground() throws Exception {
                return GuiSession.connect(registryHost, playerName, GameWindow.this);
            }
            @Override protected void done() {
                connecting = false;
                try {
                    session = get(); playerId = session.playerId();
                    if (closing) { closeSession(); return; }
                    identity.setText("Você: " + label(playerId)); identity.setForeground(BoardPanel.COLORS[playerId]);
                    cards.show(root, "game"); message("Conectado a " + registryHost + ":1099. Seu ID é " + playerId + ".");
                    refresh(); announceWinner(state == null ? null : state.winnerId());
                } catch (Exception error) {
                    state = null;
                    if (closing) { dispose(); return; }
                    connectionStatus.setText("Não foi possível entrar. Confira o servidor e tente novamente.");
                    connect.setEnabled(true); name.setEnabled(true); host.setEnabled(true);
                    JOptionPane.showMessageDialog(GameWindow.this, errorMessage(error), "Falha de conexão", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private boolean canAct() {
        return !closing && !busy && pendingVersion == null && session != null && state != null
                && state.started() && state.winnerId() == null && Integer.valueOf(playerId).equals(state.currentPlayerId())
                && state.players().stream().anyMatch(p -> p.id() == playerId && p.connected());
    }

    private void refresh() {
        boolean active = canAct();
        wallMode.setEnabled(active);
        boolean hasWalls = state != null && state.players().stream().anyMatch(p -> p.id() == playerId && p.wallsRemaining() > 0);
        wallMode.setEnabled(active && hasWalls); rotate.setEnabled(active && hasWalls && wallMode.isSelected());
        if (!hasWalls && wallMode.isSelected()) { wallMode.setSelected(false); board.wallMode(false); }
        board.update(state, playerId, active);
        if (state == null) { return; }
        for (var player : state.players()) {
            JLabel card = players[player.id()];
            // Texto literal separado do HTML evita interpretar o nome como marcação.
            card.setText("<html><b>[" + (char) ('A' + player.id()) + "] " + escape(player.joined() ? player.name() : "Aguardando")
                    + "</b><br>" + player.wallsRemaining() + " paredes"
                    + (player.joined() && !player.connected() ? " · desconectado" : "") + "</html>");
            card.setBackground(Integer.valueOf(player.id()).equals(state.currentPlayerId()) && state.winnerId() == null
                    ? new Color(221, 232, 248) : Color.WHITE);
        }
        if (state.winnerId() != null) { turn.setText("Vencedor: " + label(state.winnerId())); }
        else if (!state.started()) {
            long connected = state.players().stream().filter(GameStateDTO.PlayerDTO::connected).count();
            turn.setText("Aguardando jogadores (" + connected + "/4)");
        }
        else if (busy || pendingVersion != null) { turn.setText("Aguardando resposta do servidor…"); }
        else if (Integer.valueOf(playerId).equals(state.currentPlayerId())) { turn.setText("Sua vez — " + label(playerId)); }
        else { turn.setText("Vez de " + (state.currentPlayerId() == null ? "ninguém" : label(state.currentPlayerId()))); }
    }

    @FunctionalInterface private interface RemoteAction { void run() throws Exception; }
    private void send(RemoteAction action) {
        if (!canAct()) { return; }
        busy = true; pendingVersion = state.version(); refresh();
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception { action.run(); return null; }
            @Override protected void done() {
                busy = false;
                try { get(); }
                catch (Exception error) { pendingVersion = null; message("Pedido recusado ou falha de comunicação: " + errorMessage(error)); }
                refresh();
            }
        }.execute();
    }

    @Override public void requestMove(int row, int column) { GuiSession current = session; send(() -> current.move(row, column)); }
    @Override public void requestWall(GameStateDTO.WallDTO wall) {
        GuiSession current = session;
        send(() -> current.placeWall(wall));
    }

    @Override public void gameStarted() { if (!closing) { message("A partida começou!"); } }
    @Override public void stateUpdated(GameStateDTO update) {
        if (closing || (state != null && update.version() <= state.version())) { return; }
        state = update;
        if (pendingVersion != null && update.version() > pendingVersion) { pendingVersion = null; }
        refresh();
        if (session != null) { announceWinner(update.winnerId()); }
    }
    @Override public void gameOver(int winner) { if (!closing && session != null) { announceWinner(winner); } }
    @Override public void message(String message) {
        if (closing) { return; }
        log.append(message + "\n");
        // Evita crescimento indefinido do documento durante partidas longas.
        if (log.getDocument().getLength() > 30000) {
            try { log.getDocument().remove(0, 10000); } catch (javax.swing.text.BadLocationException ignored) { }
        }
        log.setCaretPosition(log.getDocument().getLength());
    }

    private void announceWinner(Integer winner) {
        if (winner == null || announcedWinner != null || closing) { return; }
        announcedWinner = winner;
        message("Fim do jogo. Vencedor: " + label(winner));
        JOptionPane.showMessageDialog(this, "Vencedor: " + label(winner), "Partida encerrada", JOptionPane.INFORMATION_MESSAGE);
    }

    private String label(int id) {
        String playerName = state == null ? "" : state.players().stream().filter(p -> p.id() == id)
                .map(GameStateDTO.PlayerDTO::name).findFirst().orElse("");
        return "[" + (char) ('A' + id) + "] " + playerName;
    }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String errorMessage(Exception error) {
        Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private void leave() {
        if (closing) { return; }
        closing = true; refresh(); connect.setEnabled(false);
        if (connecting) { connectionStatus.setText("Aguardando conexão para liberar a sessão…"); return; }
        closeSession();
    }
    private void closeSession() {
        GuiSession current = session;
        if (current == null) { dispose(); return; }
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() { current.close(); return null; }
            @Override protected void done() { dispose(); }
        }.execute();
    }
}
