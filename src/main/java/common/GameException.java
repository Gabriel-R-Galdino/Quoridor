package common;

import java.io.Serial;

/** Recusa de uma operação do jogo; é diferente de uma falha de transporte RMI. */
public class GameException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public GameException(String message) {
        super(message);
    }
}
