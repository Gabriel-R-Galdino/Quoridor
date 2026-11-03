package client.gui;

import common.GameStateDTO;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GuiCallbackTest {
    @Test void everyCallbackIsDeliveredOnEdtInOrder() throws Exception {
        List<String> events = new ArrayList<>();
        GuiCallback callback = new GuiCallback(new GuiCallback.Listener() {
            private void record(String event) { assertTrue(SwingUtilities.isEventDispatchThread()); events.add(event); }
            public void gameStarted() { record("start"); }
            public void stateUpdated(GameStateDTO state) { record("state"); }
            public void gameOver(int winner) { record("over"); }
            public void message(String message) { record("message"); }
        });
        callback.onGameStarted(); callback.onStateUpdate(BoardHintsTest.initial()); callback.onMessage("Teste"); callback.onGameOver(0);
        // A barreira espera os quatro invokeLater anteriores terminarem.
        SwingUtilities.invokeAndWait(() -> assertEquals(List.of("start", "state", "message", "over"), events));
    }
    @Test void remoteCallsAreRejectedOnEdtBeforeAnyNetworkAccess() throws Exception {
        SwingUtilities.invokeAndWait(() -> assertThrows(IllegalStateException.class,
                () -> GuiSession.connect("localhost", "Teste", null)));
    }
}
