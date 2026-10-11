package model;

import java.util.ArrayList;
import java.util.List;

/** Tabuleiro 9x9. Somente o motor do pacote model pode acrescentar paredes. */
public final class Board {
    public static final int SIZE = 9;
    private final List<Wall> walls = new ArrayList<>();

    public static boolean contains(int row, int column) {
        return row >= 0 && row < SIZE && column >= 0 && column < SIZE;
    }

    public List<Wall> getWalls() {
        return List.copyOf(walls);
    }

    public boolean canStep(int fromRow, int fromColumn, int toRow, int toColumn) {
        return canStep(fromRow, fromColumn, toRow, toColumn, null);
    }

    /** A parede candidata permite testar caminhos sem modificar o tabuleiro. */
    boolean canStep(int fr, int fc, int tr, int tc, Wall candidate) {
        if (!contains(fr, fc) || !contains(tr, tc) || Math.abs(fr - tr) + Math.abs(fc - tc) != 1) {
            return false;
        }
        if (candidate != null && candidate.blocks(fr, fc, tr, tc)) {
            return false;
        }
        return walls.stream().noneMatch(wall -> wall.blocks(fr, fc, tr, tc));
    }

    boolean conflictsWith(Wall candidate) {
        return walls.stream().anyMatch(candidate::conflictsWith);
    }

    void addWall(Wall wall) {
        walls.add(wall);
    }
}
