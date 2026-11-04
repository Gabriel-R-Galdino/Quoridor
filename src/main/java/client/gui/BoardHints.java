package client.gui;

import common.GameStateDTO;
import common.WallOrientation;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Projeção somente de leitura do DTO para dicas visuais. Não altera o modelo nem
 * aceita jogadas: uma prévia verde ainda precisa ser aprovada pelo servidor.
 */
final class BoardHints {
    record Cell(int row, int column) { }
    private static final int[][] DIRECTIONS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    private final GameStateDTO state;

    BoardHints(GameStateDTO state) { this.state = state; }

    /** Calcula destinos para destacar; peões desconectados ainda ocupam suas casas. */
    Set<Cell> moves(int playerId) {
        Set<Cell> result = new HashSet<>();
        var player = state.players().stream().filter(p -> p.id() == playerId).findFirst().orElse(null);
        if (player == null || !player.joined() || !player.connected()) { return Set.of(); }
        for (int[] direction : DIRECTIONS) {
            int dr = direction[0], dc = direction[1];
            Cell adjacent = new Cell(player.row() + dr, player.column() + dc);
            if (!step(player.row(), player.column(), adjacent.row(), adjacent.column(), null)) { continue; }
            if (!occupied(adjacent)) { result.add(adjacent); continue; }
            Cell behind = new Cell(adjacent.row() + dr, adjacent.column() + dc);
            if (step(adjacent.row(), adjacent.column(), behind.row(), behind.column(), null) && !occupied(behind)) {
                result.add(behind);
            } else {
                // Sem casa livre atrás do peão, o salto vira diagonal pelas laterais acessíveis.
                for (int sign : new int[]{-1, 1}) {
                    Cell side = new Cell(adjacent.row() + dc * sign, adjacent.column() + dr * sign);
                    if (step(adjacent.row(), adjacent.column(), side.row(), side.column(), null) && !occupied(side)) {
                        result.add(side);
                    }
                }
            }
        }
        return Set.copyOf(result);
    }

    /** Prévia local: verifica conflitos, estoque e caminho de todos antes de enviar ao servidor. */
    boolean wallValid(int playerId, GameStateDTO.WallDTO candidate) {
        if (candidate.row() < 0 || candidate.row() >= state.boardSize() - 1
                || candidate.column() < 0 || candidate.column() >= state.boardSize() - 1) { return false; }
        var player = state.players().stream().filter(p -> p.id() == playerId).findFirst().orElse(null);
        if (player == null || player.wallsRemaining() <= 0) { return false; }
        for (var wall : state.walls()) {
            // Orientações diferentes cruzam no mesmo ponto; iguais se sobrepõem em pontos vizinhos.
            boolean conflict = wall.orientation() != candidate.orientation()
                    ? wall.row() == candidate.row() && wall.column() == candidate.column()
                    : wall.orientation() == WallOrientation.HORIZONTAL
                        ? wall.row() == candidate.row() && Math.abs(wall.column() - candidate.column()) <= 1
                        : wall.column() == candidate.column() && Math.abs(wall.row() - candidate.row()) <= 1;
            if (conflict) { return false; }
        }
        // Inclui peões desconectados; a conectividade estrutural ignora ocupação.
        return state.players().stream().allMatch(p -> pathExists(p, candidate));
    }

    private boolean occupied(Cell cell) {
        return state.players().stream().anyMatch(p -> p.joined() && p.row() == cell.row() && p.column() == cell.column());
    }

    /** BFS que testa a parede candidata sem modificar o DTO recebido por RMI. */
    private boolean pathExists(GameStateDTO.PlayerDTO player, GameStateDTO.WallDTO candidate) {
        boolean[][] visited = new boolean[state.boardSize()][state.boardSize()];
        ArrayDeque<Cell> queue = new ArrayDeque<>();
        queue.add(new Cell(player.row(), player.column()));
        visited[player.row()][player.column()] = true;
        while (!queue.isEmpty()) {
            Cell cell = queue.remove();
            boolean goal = switch (player.side()) {
                case "NORTH" -> cell.row() == state.boardSize() - 1;
                case "SOUTH" -> cell.row() == 0;
                case "EAST" -> cell.column() == 0;
                case "WEST" -> cell.column() == state.boardSize() - 1;
                default -> false;
            };
            if (goal) { return true; }
            for (int[] direction : DIRECTIONS) {
                int row = cell.row() + direction[0], column = cell.column() + direction[1];
                if (step(cell.row(), cell.column(), row, column, candidate) && !visited[row][column]) {
                    visited[row][column] = true;
                    queue.add(new Cell(row, column));
                }
            }
        }
        return false;
    }

    private boolean step(int fr, int fc, int tr, int tc, GameStateDTO.WallDTO candidate) {
        int size = state.boardSize();
        if (fr < 0 || fc < 0 || tr < 0 || tc < 0 || fr >= size || fc >= size || tr >= size || tc >= size
                || Math.abs(fr - tr) + Math.abs(fc - tc) != 1) { return false; }
        if (candidate != null && blocks(candidate, fr, fc, tr, tc)) { return false; }
        return state.walls().stream().noneMatch(wall -> blocks(wall, fr, fc, tr, tc));
    }

    /** Uma parede bloqueia duas passagens ortogonais entre casas adjacentes. */
    private boolean blocks(GameStateDTO.WallDTO wall, int fr, int fc, int tr, int tc) {
        return wall.orientation() == WallOrientation.HORIZONTAL
                ? fc == tc && Math.min(fr, tr) == wall.row() && (fc == wall.column() || fc == wall.column() + 1)
                : fr == tr && Math.min(fc, tc) == wall.column() && (fr == wall.row() || fr == wall.row() + 1);
    }
}
