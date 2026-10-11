package model;

/** Retrato imutável de um jogador; alterações produzem uma nova instância. */
public record Player(int id, Side side, Position position, int wallsRemaining, boolean connected) {
    /** Lado de origem, que determina o objetivo no lado oposto. */
    public enum Side {
        NORTH, SOUTH, EAST, WEST;

        public boolean isGoal(Position position) {
            return switch (this) {
                case NORTH -> position.row() == 8;
                case SOUTH -> position.row() == 0;
                case EAST -> position.column() == 0;
                case WEST -> position.column() == 8;
            };
        }
    }

    public Player {
        if (id < 0 || id > 3 || side == null || position == null
                || wallsRemaining < 0 || wallsRemaining > 5) {
            throw new IllegalArgumentException("Dados inválidos do jogador.");
        }
    }

    Player moveTo(Position destination) {
        return new Player(id, side, destination, wallsRemaining, connected);
    }

    Player useWall() {
        return new Player(id, side, position, wallsRemaining - 1, connected);
    }

    Player disconnect() {
        return new Player(id, side, position, wallsRemaining, false);
    }
}
