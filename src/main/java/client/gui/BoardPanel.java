package client.gui;

import common.GameStateDTO;
import common.WallOrientation;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Set;

/** Tabuleiro desenhado por Java2D. Eventos e pintura executam exclusivamente na EDT. */
final class BoardPanel extends JPanel {
    static final Color[] COLORS = {new Color(38, 105, 207), new Color(210, 74, 76),
            new Color(38, 146, 107), new Color(156, 98, 197)};
    interface Actions {
        void requestMove(int row, int column);
        void requestWall(GameStateDTO.WallDTO wall);
    }
    private final Actions actions;
    private GameStateDTO state;
    private int localId = -1;
    private boolean interactive;
    private boolean wallMode;
    private WallOrientation orientation = WallOrientation.HORIZONTAL;
    private Set<BoardHints.Cell> moves = Set.of();
    private GameStateDTO.WallDTO hover;
    private boolean hoverValid;
    private Point mouse;

    BoardPanel(Actions actions) {
        this.actions = actions;
        setPreferredSize(new Dimension(650, 650));
        setBackground(new Color(239, 242, 247));
        setToolTipText("Na sua vez, clique em uma casa destacada. No modo parede, use as bordas entre casas.");
        MouseAdapter handler = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent event) { mouse = event.getPoint(); updateHover(); }
            @Override public void mouseExited(MouseEvent event) { mouse = null; hover = null; repaint(); }
            @Override public void mouseClicked(MouseEvent event) {
                if (!interactive || !SwingUtilities.isLeftMouseButton(event)) { return; }
                if (wallMode) {
                    mouse = event.getPoint(); updateHover();
                    if (hover != null && hoverValid) { actions.requestWall(hover); }
                } else {
                    Layout board = layoutBoard();
                    int row = (int) Math.floor((event.getY() - board.y) / board.pitch);
                    int column = (int) Math.floor((event.getX() - board.x) / board.pitch);
                    if (moves.contains(new BoardHints.Cell(row, column)) && cellBounds(board, row, column).contains(event.getPoint())) {
                        actions.requestMove(row, column);
                    }
                }
            }
        };
        addMouseListener(handler);
        addMouseMotionListener(handler);
        bindArrow("UP", -1, 0); bindArrow("DOWN", 1, 0); bindArrow("LEFT", 0, -1); bindArrow("RIGHT", 0, 1);
    }

    void update(GameStateDTO state, int localId, boolean interactive) {
        this.state = state; this.localId = localId; this.interactive = interactive;
        moves = interactive ? new BoardHints(state).moves(localId) : Set.of();
        updateHover();
        repaint();
    }

    void wallMode(boolean selected) { wallMode = selected; updateHover(); repaint(); }
    void orientation(WallOrientation orientation) { this.orientation = orientation; updateHover(); repaint(); }

    private void bindArrow(String key, int dr, int dc) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        getActionMap().put(key, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                if (!interactive || wallMode || state == null) { return; }
                var player = state.players().stream().filter(p -> p.id() == localId).findFirst().orElseThrow();
                // A mesma seta pode representar um passo ou um salto reto sobre outro peão.
                for (int distance = 1; distance <= 2; distance++) {
                    int row = player.row() + dr * distance, column = player.column() + dc * distance;
                    if (moves.contains(new BoardHints.Cell(row, column))) { actions.requestMove(row, column); return; }
                }
            }
        });
    }

    private record Layout(double x, double y, double pitch, double gap) { }
    private Layout layoutBoard() {
        // pitch é o espaço de cada casa; gap reserva as faixas onde as paredes aparecem.
        double side = Math.max(90, Math.min(getWidth(), getHeight()) - 72);
        double pitch = side / 9;
        return new Layout((getWidth() - side) / 2, (getHeight() - side) / 2, pitch, pitch * .16);
    }

    private Rectangle.Double cellBounds(Layout board, int row, int column) {
        return new Rectangle.Double(board.x + column * board.pitch + board.gap / 2,
                board.y + row * board.pitch + board.gap / 2, board.pitch - board.gap, board.pitch - board.gap);
    }

    private Rectangle.Double wallBounds(Layout board, GameStateDTO.WallDTO wall) {
        double thickness = board.gap * .85;
        return wall.orientation() == WallOrientation.HORIZONTAL
                ? new Rectangle.Double(board.x + wall.column() * board.pitch + board.gap / 2,
                    board.y + (wall.row() + 1) * board.pitch - thickness / 2, 2 * board.pitch - board.gap, thickness)
                : new Rectangle.Double(board.x + (wall.column() + 1) * board.pitch - thickness / 2,
                    board.y + wall.row() * board.pitch + board.gap / 2, thickness, 2 * board.pitch - board.gap);
    }

    private void updateHover() {
        hover = null;
        if (mouse != null && state != null && interactive && wallMode) {
            Layout board = layoutBoard();
            double x = (mouse.x - board.x) / board.pitch, y = (mouse.y - board.y) / board.pitch;
            if (x >= 0 && x < 9 && y >= 0 && y < 9) {
                // Encaixa na borda mais próxima; a tolerância abaixo evita prévias no meio da casa.
                int row = orientation == WallOrientation.HORIZONTAL ? (int) Math.round(y) - 1 : Math.min(7, (int) Math.floor(y));
                int column = orientation == WallOrientation.VERTICAL ? (int) Math.round(x) - 1 : Math.min(7, (int) Math.floor(x));
                double edgeDistance = orientation == WallOrientation.HORIZONTAL ? Math.abs(y - Math.rint(y)) : Math.abs(x - Math.rint(x));
                if (row >= 0 && row < 8 && column >= 0 && column < 8 && edgeDistance <= .22) {
                    hover = new GameStateDTO.WallDTO(row, column, orientation);
                    hoverValid = new BoardHints(state).wallValid(localId, hover);
                }
            }
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        // A cópia de Graphics2D isola cores e traços usados nesta pintura; é liberada no finally.
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Layout board = layoutBoard();
            g.setColor(new Color(224, 214, 196));
            g.fill(new RoundRectangle2D.Double(board.x - 12, board.y - 12, board.pitch * 9 + 24, board.pitch * 9 + 24, 22, 22));
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) {
                    var cell = cellBounds(board, row, column);
                    g.setColor(new Color(252, 247, 237)); g.fill(cell);
                    if (state != null) {
                        // Camadas transparentes permitem mostrar objetivos compartilhados nos cantos.
                        for (var player : state.players()) {
                            boolean goal = switch (player.side()) {
                                case "NORTH" -> row == 8; case "SOUTH" -> row == 0;
                                case "EAST" -> column == 0; case "WEST" -> column == 8; default -> false;
                            };
                            if (goal) { Color color = COLORS[player.id()]; g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 42)); g.fill(cell); }
                        }
                    }
                    if (!wallMode && moves.contains(new BoardHints.Cell(row, column))) {
                        g.setColor(new Color(38, 146, 107, 65)); g.fill(cell);
                        g.setColor(new Color(31, 137, 95)); g.setStroke(new BasicStroke(2)); g.draw(cell);
                        int diameter = (int) (board.pitch * .16);
                        g.fillOval((int) cell.getCenterX() - diameter / 2, (int) cell.getCenterY() - diameter / 2, diameter, diameter);
                    }
                }
                g.setColor(new Color(96, 101, 113));
                g.drawString(String.valueOf(row), (float) (board.x - 29), (float) (board.y + row * board.pitch + board.pitch / 2 + 5));
                g.drawString(String.valueOf(row), (float) (board.x + row * board.pitch + board.pitch / 2 - 3), (float) (board.y - 22));
            }
            if (state == null) { return; }
            for (var wall : state.walls()) { g.setColor(new Color(75, 66, 54)); g.fill(wallBounds(board, wall)); }
            for (var player : state.players()) {
                if (!player.joined()) { continue; }
                var cell = cellBounds(board, player.row(), player.column());
                int diameter = (int) (board.pitch * .6), x = (int) cell.getCenterX() - diameter / 2, y = (int) cell.getCenterY() - diameter / 2;
                if (player.id() == localId) { g.setColor(Color.WHITE); g.fillOval(x - 4, y - 4, diameter + 8, diameter + 8); }
                g.setColor(COLORS[player.id()]); g.fillOval(x, y, diameter, diameter);
                g.setColor(Color.WHITE); g.setFont(getFont().deriveFont(Font.BOLD, (float) (board.pitch * .26)));
                g.drawString(String.valueOf((char) ('A' + player.id())), (float) cell.getCenterX() - 5, (float) cell.getCenterY() + 6);
                if (!player.connected()) { g.setColor(Color.DARK_GRAY); g.drawLine(x, y, x + diameter, y + diameter); g.drawLine(x + diameter, y, x, y + diameter); }
            }
            if (hover != null) {
                g.setColor(hoverValid ? new Color(29, 158, 91, 195) : new Color(213, 50, 57, 195));
                g.fill(wallBounds(board, hover));
            }
        } finally { g.dispose(); }
    }
}
