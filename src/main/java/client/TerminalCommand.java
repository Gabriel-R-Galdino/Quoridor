package client;

import common.GameStateDTO;
import common.WallOrientation;

import java.util.Locale;

/** Converte texto em intenções; a validade das jogadas continua sendo do servidor. */
sealed interface TerminalCommand {
    record Move(int rowDelta, int columnDelta) implements TerminalCommand {
        /**
         * Uma direção cardinal aponta para depois do peão vizinho quando ele existe.
         * Isso apenas escolhe o destino do pedido; não verifica paredes nem regras.
         * As diagonais têm destino explícito e nunca saltam dois peões.
         */
        int[] destination(GameStateDTO state, int playerId) {
            GameStateDTO.PlayerDTO player = state.players().stream()
                    .filter(p -> p.id() == playerId).findFirst().orElseThrow();
            int row = player.row() + rowDelta, column = player.column() + columnDelta;
            boolean cardinal = rowDelta == 0 || columnDelta == 0;
            boolean occupied = state.players().stream().anyMatch(p ->
                    p.joined() && p.row() == row && p.column() == column);
            int distance = cardinal && occupied ? 2 : 1;
            return new int[]{player.row() + distance * rowDelta, player.column() + distance * columnDelta};
        }
    }

    record PlaceWall(int row, int column, WallOrientation orientation) implements TerminalCommand { }
    record Quit() implements TerminalCommand { }

    static TerminalCommand parse(String line) {
        String[] words = line.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 1 && words[0].equals("q")) { return new Quit(); }
        if (words.length == 2 && words[0].equals("m")) {
            return switch (words[1]) {
                case "w" -> new Move(-1, 0);
                case "a" -> new Move(0, -1);
                case "s" -> new Move(1, 0);
                case "d" -> new Move(0, 1);
                // Necessários para escolher uma lateral quando o salto reto está impedido.
                case "wa", "aw" -> new Move(-1, -1);
                case "wd", "dw" -> new Move(-1, 1);
                case "sa", "as" -> new Move(1, -1);
                case "sd", "ds" -> new Move(1, 1);
                default -> throw new IllegalArgumentException("Direção inválida. Use w, a, s, d ou wa, wd, sa, sd.");
            };
        }
        if (words.length == 4 && words[0].equals("p")) {
            WallOrientation orientation = switch (words[3]) {
                case "h" -> WallOrientation.HORIZONTAL;
                case "v" -> WallOrientation.VERTICAL;
                default -> throw new IllegalArgumentException("Orientação inválida. Use h ou v.");
            };
            try {
                return new PlaceWall(Integer.parseInt(words[1]), Integer.parseInt(words[2]), orientation);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Linha e coluna devem ser números inteiros.");
            }
        }
        throw new IllegalArgumentException("Comando inválido. Use m w|a|s|d, p L C h|v ou q.");
    }
}
