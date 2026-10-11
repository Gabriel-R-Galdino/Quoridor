package model;

/** Casa imutável. Linhas e colunas vão de 0 a 8; o norte fica na linha 0. */
public record Position(int row, int column) {
    public Position {
        if (!Board.contains(row, column)) {
            throw new IllegalArgumentException("Casa fora do tabuleiro.");
        }
    }
}
