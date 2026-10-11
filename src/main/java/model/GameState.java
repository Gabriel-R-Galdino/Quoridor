package model;

import java.util.ArrayList;
import java.util.List;

/** Estado oficial local. A API pública permite leitura, e o motor controla alterações. */
public final class GameState {
    private final Board board = new Board();
    private final List<Player> players = new ArrayList<>(List.of(
            new Player(0, Player.Side.NORTH, new Position(0, 4), 5, true),
            new Player(1, Player.Side.SOUTH, new Position(8, 4), 5, true),
            new Player(2, Player.Side.EAST, new Position(4, 8), 5, true),
            new Player(3, Player.Side.WEST, new Position(4, 0), 5, true)));
    private int currentIndex;
    private Integer winnerId;

    public Board getBoard() { return board; }
    public List<Player> getPlayers() { return List.copyOf(players); }
    public Player getPlayer(int id) { return players.get(id); }

    public Player currentPlayer() {
        return currentIndex < 0 ? null : players.get(currentIndex);
    }

    public Player getWinner() {
        return winnerId == null ? null : players.get(winnerId);
    }

    void replacePlayer(Player player) { players.set(player.id(), player); }
    void setWinner(int id) { winnerId = id; }

    /** Ordem fixa: norte (0), sul (1), leste (2), oeste (3). */
    void advanceTurn() {
        for (int offset = 1; offset <= 4; offset++) {
            int next = (currentIndex + offset) % 4;
            if (players.get(next).connected()) {
                currentIndex = next;
                return;
            }
        }
        currentIndex = -1; // Sem clientes conectados, não há jogador da vez.
    }
}
