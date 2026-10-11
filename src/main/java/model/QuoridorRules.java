package model;

import java.util.ArrayDeque;
import java.util.Objects;

/**
 * Motor autoritativo local, sem RMI. Pedidos inválidos retornam false sem alterar nada.
 * O futuro servidor deverá serializar o acesso a esta instância entre chamadas remotas.
 */
public final class QuoridorRules {
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private final GameState state;

    public QuoridorRules() { this(new GameState()); }

    // Permite preparar cenários nos testes do próprio pacote, sem API pública de mutação.
    QuoridorRules(GameState state) { this.state = Objects.requireNonNull(state); }

    public GameState getState() { return state; }
    public Player currentPlayer() { return state.currentPlayer(); }
    public Player getWinner() { return state.getWinner(); }

    private boolean canAct(int playerId) {
        Player current = currentPlayer();
        return getWinner() == null && current != null && current.id() == playerId && current.connected();
    }

    /** Tenta mover o jogador identificado até uma casa absoluta, com índices de 0 a 8. */
    public boolean tryMove(int playerId, int row, int column) {
        if (!canAct(playerId) || !Board.contains(row, column) || occupied(row, column)) {
            return false;
        }
        Player player = currentPlayer();
        if (!legalMove(player.position(), row, column)) {
            return false;
        }
        Position destination = new Position(row, column);
        state.replacePlayer(player.moveTo(destination));
        if (player.side().isGoal(destination)) {
            state.setWinner(playerId);
        } else {
            state.advanceTurn();
        }
        return true;
    }

    private boolean occupied(int row, int column) {
        // Um peão desconectado continua no tabuleiro e participa dos saltos.
        return state.getPlayers().stream().anyMatch(player ->
                player.position().row() == row && player.position().column() == column);
    }

    private boolean legalMove(Position from, int row, int column) {
        Board board = state.getBoard();
        for (int[] direction : DIRECTIONS) {
            int dr = direction[0], dc = direction[1];
            int ar = from.row() + dr, ac = from.column() + dc;
            if (!board.canStep(from.row(), from.column(), ar, ac)) {
                continue; // Nem mesmo o acesso ao peão adjacente pode atravessar parede.
            }
            if (!occupied(ar, ac)) {
                if (row == ar && column == ac) { return true; }
                continue;
            }
            int behindRow = ar + dr, behindColumn = ac + dc;
            boolean straightJump = board.canStep(ar, ac, behindRow, behindColumn)
                    && !occupied(behindRow, behindColumn);
            if (straightJump) {
                if (row == behindRow && column == behindColumn) { return true; }
            } else {
                // Parede, borda ou outro peão atrás impede o salto reto.
                // Nesse caso, só se pode contornar UM peão pelas suas laterais.
                for (int sign : new int[]{-1, 1}) {
                    int sideRow = ar + dc * sign, sideColumn = ac + dr * sign;
                    if (row == sideRow && column == sideColumn
                            && board.canStep(ar, ac, sideRow, sideColumn)
                            && !occupied(sideRow, sideColumn)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Tenta colocar uma parede. A ancoragem vai de 0 a 7 nos dois eixos. */
    public boolean tryPlaceWall(int playerId, int row, int column, Wall.Orientation orientation) {
        if (!canAct(playerId) || currentPlayer().wallsRemaining() == 0
                || row < 0 || row >= 8 || column < 0 || column >= 8 || orientation == null) {
            return false;
        }
        Wall candidate = new Wall(row, column, orientation);
        Board board = state.getBoard();
        if (board.conflictsWith(candidate)) { return false; }
        // Inclui os desconectados: todos os quatro objetivos devem continuar acessíveis.
        for (Player player : state.getPlayers()) {
            if (!hasPath(player, candidate)) { return false; }
        }
        board.addWall(candidate);
        state.replacePlayer(currentPlayer().useWall());
        state.advanceTurn();
        return true;
    }

    /**
     * BFS sobre passagens do tabuleiro. Peões são ignorados nesta validação:
     * o requisito é um caminho estrutural até o objetivo, independente da ocupação.
     */
    private boolean hasPath(Player player, Wall candidate) {
        boolean[][] visited = new boolean[Board.SIZE][Board.SIZE];
        ArrayDeque<Position> queue = new ArrayDeque<>();
        queue.add(player.position());
        visited[player.position().row()][player.position().column()] = true;
        while (!queue.isEmpty()) {
            Position position = queue.remove();
            if (player.side().isGoal(position)) { return true; }
            for (int[] direction : DIRECTIONS) {
                int row = position.row() + direction[0], column = position.column() + direction[1];
                if (state.getBoard().canStep(position.row(), position.column(), row, column, candidate)
                        && !visited[row][column]) {
                    visited[row][column] = true;
                    queue.add(new Position(row, column));
                }
            }
        }
        return false;
    }

    /** Marca uma desconexão; preserva o peão e pula imediatamente seu turno, se necessário. */
    public boolean markDisconnected(int playerId) {
        if (playerId < 0 || playerId > 3 || !state.getPlayer(playerId).connected()) { return false; }
        boolean wasCurrent = currentPlayer() != null && currentPlayer().id() == playerId;
        state.replacePlayer(state.getPlayer(playerId).disconnect());
        if (wasCurrent && getWinner() == null) { state.advanceTurn(); }
        return true;
    }

    /** Desenho exclusivamente textual; peões 1 a 4 correspondem aos IDs 0 a 3. */
    public String renderAscii() {
        StringBuilder text = new StringBuilder("    0 1 2 3 4 5 6 7 8\n");
        Board board = state.getBoard();
        for (int row = 0; row < Board.SIZE; row++) {
            text.append(row).append("   ");
            for (int column = 0; column < Board.SIZE; column++) {
                char cell = '.';
                for (Player player : state.getPlayers()) {
                    if (player.position().equals(new Position(row, column))) {
                        cell = (char) ('1' + player.id());
                    }
                }
                text.append(cell);
                if (column < 8) { text.append(board.canStep(row, column, row, column + 1) ? ' ' : '|'); }
            }
            text.append('\n');
            if (row < 8) {
                text.append("    ");
                for (int column = 0; column < Board.SIZE; column++) {
                    text.append(board.canStep(row, column, row + 1, column) ? ' ' : '-');
                    if (column < 8) { text.append('+'); }
                }
                text.append('\n');
            }
        }
        for (Player player : state.getPlayers()) {
            text.append("Jogador ").append(player.id() + 1).append(" (").append(player.side())
                    .append("): ").append(player.wallsRemaining()).append(" paredes")
                    .append(player.connected() ? "\n" : " [desconectado]\n");
        }
        if (getWinner() != null) {
            text.append("Vencedor: jogador ").append(getWinner().id() + 1).append('\n');
        } else if (currentPlayer() != null) {
            text.append("Turno: jogador ").append(currentPlayer().id() + 1).append('\n');
        } else {
            text.append("Sem jogadores conectados.\n");
        }
        return text.toString();
    }
}
