package client.gui;

import common.GameStateDTO;
import common.WallOrientation;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.Set;

/** Tabuleiro desenhado por Java2D. Eventos e pintura executam exclusivamente na Event Dispatch Thread - EDT. */
final class BoardPanel extends JPanel {
    static final Color[] COLORS = {new Color(248, 202, 52), new Color(201, 46, 69),
            new Color(44, 184, 205), new Color(244, 136, 149)};
    private static final Color BOARD_BACKGROUND = new Color(22, 35, 50);
    private static final Color GRID = new Color(104, 123, 142);
    private static final Color CELL = new Color(28, 45, 64);
    private static final Color COORDINATE = new Color(217, 227, 236);
    private static final Color WALL = new Color(255, 224, 161);
    private static final Color OUTLINE = new Color(50, 43, 45);
    private static final Color HALO = new Color(230, 237, 245);
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
            g.setColor(BOARD_BACKGROUND); g.fillRect(0, 0, getWidth(), getHeight());
            Layout board = layoutBoard();
            g.setColor(GRID);
            g.fill(new RoundRectangle2D.Double(board.x - 12, board.y - 12, board.pitch * 9 + 24, board.pitch * 9 + 24, 22, 22));
            g.setFont(getFont().deriveFont(Font.BOLD, 12f));
            for (int row = 0; row < 9; row++) {
                for (int column = 0; column < 9; column++) {
                    var cell = cellBounds(board, row, column);
                    g.setColor(CELL); g.fill(cell);
                    if (state != null) {
                        // Camadas transparentes permitem mostrar objetivos compartilhados nos cantos.
                        for (var player : state.players()) {
                            boolean goal = switch (player.side()) {
                                case "NORTH" -> row == 8; case "SOUTH" -> row == 0;
                                case "EAST" -> column == 0; case "WEST" -> column == 8; default -> false;
                            };
                            if (goal) {
                                Color color = COLORS[player.id()];
                                Color goalColor = new Color((color.getRed() * 45 + 255 * 55) / 100,
                                        (color.getGreen() * 45 + 255 * 55) / 100,
                                        (color.getBlue() * 45 + 255 * 55) / 100);
                                g.setColor(new Color(goalColor.getRed(), goalColor.getGreen(), goalColor.getBlue(), 155)); g.fill(cell);
                                g.setColor(goalColor); g.setStroke(new BasicStroke(1.8f)); g.draw(cell);
                            }
                        }
                    }
                    if (!wallMode && moves.contains(new BoardHints.Cell(row, column))) {
                        g.setColor(new Color(73, 230, 161, 75)); g.fill(cell);
                        g.setColor(new Color(73, 230, 161)); g.setStroke(new BasicStroke(2.4f)); g.draw(cell);
                        int diameter = (int) (board.pitch * .16);
                        g.fillOval((int) cell.getCenterX() - diameter / 2, (int) cell.getCenterY() - diameter / 2, diameter, diameter);
                    }
                }
                g.setColor(COORDINATE);
                g.drawString(String.valueOf(row), (float) (board.x - 29), (float) (board.y + row * board.pitch + board.pitch / 2 + 5));
                g.drawString(String.valueOf(row), (float) (board.x + row * board.pitch + board.pitch / 2 - 3), (float) (board.y - 22));
            }
            if (state == null) { return; }
            for (var wall : state.walls()) { g.setColor(WALL); g.fill(wallBounds(board, wall)); }
            for (var player : state.players()) {
                if (!player.joined()) { continue; }
                var cell = cellBounds(board, player.row(), player.column());
                double size = board.pitch * .64, x = cell.getCenterX() - size / 2, y = cell.getCenterY() - size / 2;
                Color outline = OUTLINE;
                if (player.id() == localId) {
                    g.setColor(HALO);
                    g.fillOval((int) (x - 5), (int) (y - 5), (int) (size + 10), (int) (size + 10));
                }
                g.setColor(COLORS[player.id()]);
                g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (player.id() == 0) {
                    // O arco de 306 graus deixa uma abertura de 54 graus voltada à direita.
                    var body = new java.awt.geom.Arc2D.Double(x, y, size, size, 27, 306, java.awt.geom.Arc2D.PIE);
                    g.fill(body);
                    g.setColor(HALO); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(body);
                    g.setColor(outline); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(body);
                    g.setColor(outline);
                    g.fillOval((int) (x + size * .43), (int) (y + size * .16),
                            Math.max(3, (int) (size * .09)), Math.max(3, (int) (size * .09)));
                } else {
                    java.awt.geom.Path2D.Double ghost = new java.awt.geom.Path2D.Double();
                    ghost.moveTo(x + size * .08, y + size * .91);
                    ghost.lineTo(x + size * .08, y + size * .48);
                    ghost.curveTo(x + size * .08, y - size * .12, x + size * .92, y - size * .12,
                            x + size * .92, y + size * .48);
                    ghost.lineTo(x + size * .92, y + size * .91);
                    ghost.quadTo(x + size * .80, y + size * .73, x + size * .68, y + size * .91);
                    ghost.quadTo(x + size * .56, y + size * .73, x + size * .44, y + size * .91);
                    ghost.quadTo(x + size * .30, y + size * .73, x + size * .20, y + size * .91);
                    ghost.quadTo(x + size * .13, y + size * .81, x + size * .08, y + size * .91);
                    ghost.closePath();
                    g.fill(ghost);
                    g.setColor(HALO); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(ghost);
                    g.setColor(outline); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); g.draw(ghost);
                    g.setColor(Color.WHITE);
                    g.fillOval((int) (x + size * .26), (int) (y + size * .31), (int) (size * .18), (int) (size * .23));
                    g.fillOval((int) (x + size * .56), (int) (y + size * .31), (int) (size * .18), (int) (size * .23));
                    g.setColor(outline);
                    g.fillOval((int) (x + size * .33), (int) (y + size * .40), Math.max(3, (int) (size * .07)), Math.max(3, (int) (size * .07)));
                    g.fillOval((int) (x + size * .63), (int) (y + size * .40), Math.max(3, (int) (size * .07)), Math.max(3, (int) (size * .07)));
                }
                g.setFont(getFont().deriveFont(Font.BOLD, (float) (board.pitch * .22)));
                String letter = String.valueOf((char) ('A' + player.id()));
                FontMetrics metrics = g.getFontMetrics();
                float textX = (float) (cell.getCenterX() - metrics.stringWidth(letter) / 2.0 - (player.id() == 0 ? size * .10 : 0));
                float textY = (float) (y + size * .78);
                g.setColor(player.id() == 1 ? Color.WHITE : outline);
                g.drawString(letter, textX, textY);
                if (!player.connected()) {
                    g.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(Color.WHITE);
                    g.drawLine((int) x, (int) y, (int) (x + size), (int) (y + size));
                    g.drawLine((int) (x + size), (int) y, (int) x, (int) (y + size));
                    g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(outline);
                    g.drawLine((int) x, (int) y, (int) (x + size), (int) (y + size));
                    g.drawLine((int) (x + size), (int) y, (int) x, (int) (y + size));
                }
            }
            if (hover != null) {
                var preview = wallBounds(board, hover);
                // O filete escuro separa a prévia colorida da grade de ardósia.
                g.setColor(CELL);
                g.fill(new RoundRectangle2D.Double(preview.x - 2, preview.y - 2,
                        preview.width + 4, preview.height + 4, 5, 5));
                g.setColor(hoverValid ? new Color(73, 230, 161) : new Color(255, 102, 119));
                g.fill(preview);
            }
        } finally { g.dispose(); }
    }
}
