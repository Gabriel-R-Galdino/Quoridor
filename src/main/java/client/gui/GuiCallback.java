package client.gui;

import common.ClientCallback;
import common.GameStateDTO;
import javax.swing.SwingUtilities;

/** O RMI chama threads próprias; nenhuma delas toca os componentes Swing. */
final class GuiCallback implements ClientCallback {
    interface Listener {
        void gameStarted();
        void stateUpdated(GameStateDTO state);
        void gameOver(int winner);
        void message(String message);
    }

    private final Listener listener;
    GuiCallback(Listener listener) { this.listener = listener; }

    @Override public void onGameStarted() { SwingUtilities.invokeLater(listener::gameStarted); }
    @Override public void onStateUpdate(GameStateDTO state) { SwingUtilities.invokeLater(() -> listener.stateUpdated(state)); }
    @Override public void onGameOver(int winner) { SwingUtilities.invokeLater(() -> listener.gameOver(winner)); }
    @Override public void onMessage(String message) { SwingUtilities.invokeLater(() -> listener.message(message)); }
}
