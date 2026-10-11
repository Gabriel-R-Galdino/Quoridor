package common;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * Retrato imutável enviado por valor pelo RMI, suficiente para desenhar o tabuleiro.
 * A versão cresce a cada evento de estado; o cliente pode descartar versões antigas.
 * IDs são de 0 a 3. Turno e vencedor podem ser null; casas vão de 0 a 8.
 */
public record GameStateDTO(long version, int boardSize, boolean started,
                           Integer currentPlayerId, Integer winnerId,
                           List<PlayerDTO> players, List<WallDTO> walls) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public GameStateDTO {
        players = List.copyOf(players);
        walls = List.copyOf(walls);
    }

    /** Lado é NORTH, SOUTH, EAST ou WEST; o objetivo é o lado oposto. */
    public record PlayerDTO(int id, String name, String side, int row, int column,
                            int wallsRemaining, boolean joined, boolean connected) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    /** Interseção da parede de 0 a 7; mesma convenção do motor de regras. */
    public record WallDTO(int row, int column, WallOrientation orientation) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
