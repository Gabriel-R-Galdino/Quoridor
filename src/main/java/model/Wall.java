package model;

/**
 * Parede ancorada em uma interseção de 0 a 7 em cada eixo.
 * HORIZONTAL bloqueia (linha,coluna)-(linha+1,coluna) e a passagem à direita.
 * VERTICAL bloqueia (linha,coluna)-(linha,coluna+1) e a passagem abaixo.
 */
public record Wall(int row, int column, Orientation orientation) {
    public enum Orientation { HORIZONTAL, VERTICAL }

    public Wall {
        if (row < 0 || row >= 8 || column < 0 || column >= 8 || orientation == null) {
            throw new IllegalArgumentException("Parede fora das interseções do tabuleiro.");
        }
    }

    /** Contato entre pontas é permitido; segmento compartilhado e cruzamento não. */
    boolean conflictsWith(Wall other) {
        if (orientation != other.orientation) {
            return row == other.row && column == other.column;
        }
        return orientation == Orientation.HORIZONTAL
                ? row == other.row && Math.abs(column - other.column) <= 1
                : column == other.column && Math.abs(row - other.row) <= 1;
    }

    boolean blocks(int fromRow, int fromColumn, int toRow, int toColumn) {
        return orientation == Orientation.HORIZONTAL
                ? fromColumn == toColumn && Math.min(fromRow, toRow) == row
                    && (fromColumn == column || fromColumn == column + 1)
                : fromRow == toRow && Math.min(fromColumn, toColumn) == column
                    && (fromRow == row || fromRow == row + 1);
    }
}
