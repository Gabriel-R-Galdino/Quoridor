package client.gui;

import common.GameStateDTO;
import common.GameService;
import common.WallOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import server.GameServerImpl;
import javax.swing.SwingUtilities;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

/** A sessão Swing conversa com o servidor existente, sem mudar qualquer contrato. */
@Timeout(20)
class GuiSessionTest {
    private static class Listener implements GuiCallback.Listener {
        final List<GameStateDTO> states = new ArrayList<>();
        volatile boolean wrongThread;
        private void check() { if (!SwingUtilities.isEventDispatchThread()) { wrongThread = true; } }
        public void gameStarted() { check(); }
        public void gameOver(int winner) { check(); }
        public void message(String message) { check(); }
        public synchronized void stateUpdated(GameStateDTO state) { check(); states.add(state); notifyAll(); }
        synchronized GameStateDTO await(Predicate<GameStateDTO> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                for (var state : states) { if (predicate.test(state)) { return state; } }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { fail("Não recebeu o estado esperado via callback Swing."); }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }
    @Test void fourGuiSessionsReceiveEdtUpdatesAndSendRequestsOffEdt() throws Exception {
        var registry = LocateRegistry.createRegistry(1099);
        List<GuiSession> sessions = new ArrayList<>();
        try (GameServerImpl server = new GameServerImpl()) {
            try {
                registry.rebind(GameService.SERVICE_NAME, UnicastRemoteObject.exportObject(server, 0));
                Listener first = new Listener();
                for (int id = 0; id < 4; id++) {
                    GuiSession session = GuiSession.connect("localhost", "GUI " + id, id == 0 ? first : new Listener());
                    assertEquals(id, session.playerId()); sessions.add(session);
                }
                first.await(GameStateDTO::started);
                sessions.get(0).move(1, 4);
                first.await(state -> state.players().get(0).row() == 1);
                sessions.get(1).placeWall(new GameStateDTO.WallDTO(3, 0, WallOrientation.HORIZONTAL));
                first.await(state -> state.walls().size() == 1);
                sessions.get(2).close();
                assertEquals(3, first.await(state -> state.version() > 6 && !state.players().get(2).connected()).currentPlayerId());
                assertFalse(first.wrongThread);
            } finally {
                for (GuiSession session : sessions) { session.close(); }
                UnicastRemoteObject.unexportObject(server, true);
            }
        } finally { UnicastRemoteObject.unexportObject(registry, true); }
    }
}
