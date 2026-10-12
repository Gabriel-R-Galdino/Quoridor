package client;

import common.ClientCallback;
import common.GameStateDTO;
import java.util.Objects;

/** Encaminha callbacks RMI à fila do terminal e retorna sem esperar pelo teclado. */
public final class ClientCallbackImpl implements ClientCallback {
    private final TerminalUI terminal;

    public ClientCallbackImpl(TerminalUI terminal) {
        this.terminal = Objects.requireNonNull(terminal);
    }
    @Override
    public void onGameStarted() {
        terminal.onGameStarted();
    }

    @Override
    public void onStateUpdate(GameStateDTO state) {
        terminal.onStateUpdate(state);
    }

    @Override
    public void onGameOver(int vencedor) {
        terminal.onGameOver(vencedor);
    }

    @Override
    public void onMessage(String message) {
        terminal.onMessage(message);
    }
}
