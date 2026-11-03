package server;

import common.ClientCallback;
import common.GameException;
import common.GameService;
import common.GameStateDTO;
import common.WallOrientation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RemoteObject;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Verifica regras do serviço, concorrência e uma comunicação RMI real em localhost. */
@Timeout(15)
class GameServerImplTest {
    private static class RecordingCallback implements ClientCallback {
        private final List<GameStateDTO> states = new ArrayList<>();
        private final List<Integer> winners = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private int starts;
        volatile String failOn = "";

        private void failIf(String method) throws RemoteException {
            if (method.equals(failOn)) { throw new RemoteException("Cliente caiu em " + method); }
        }

        @Override
        public synchronized void onGameStarted() throws RemoteException {
            failIf("onGameStarted");
            starts++;
        }

        @Override
        public synchronized void onStateUpdate(GameStateDTO state) throws RemoteException {
            failIf("onStateUpdate");
            states.add(state);
            notifyAll();
        }

        @Override
        public synchronized void onGameOver(int winner) throws RemoteException {
            failIf("onGameOver");
            winners.add(winner);
            notifyAll();
        }

        @Override
        public synchronized void onMessage(String message) throws RemoteException {
            failIf("onMessage");
            messages.add(message);
            notifyAll();
        }

        synchronized void awaitMessage(String text) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (messages.stream().noneMatch(message -> message.contains(text))) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { fail("Não recebeu a mensagem: " + text); }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        synchronized GameStateDTO awaitState(Predicate<GameStateDTO> predicate) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                for (GameStateDTO state : states) {
                    if (predicate.test(state)) { return state; }
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { fail("O callback não recebeu o estado esperado."); }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }

        synchronized void awaitWinner(int id) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!winners.contains(id)) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) { fail("O callback não recebeu a vitória."); }
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
        }
    }

    private static RecordingCallback[] joinFour(GameService service) throws Exception {
        RecordingCallback[] callbacks = new RecordingCallback[4];
        for (int id = 0; id < 4; id++) {
            callbacks[id] = new RecordingCallback();
            assertEquals(id, service.join("Jogador " + id, callbacks[id]));
        }
        for (RecordingCallback callback : callbacks) { callback.awaitState(GameStateDTO::started); }
        return callbacks;
    }

    @Test
    void fourthJoinStartsExactlyOnceAndBroadcastsInitialState() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            for (RecordingCallback callback : callbacks) {
                GameStateDTO state = callback.awaitState(GameStateDTO::started);
                assertEquals(1, callback.starts);
                assertEquals(9, state.boardSize());
                assertEquals(0, state.currentPlayerId());
                assertNull(state.winnerId());
                assertEquals(4, state.players().size());
                assertTrue(state.players().stream().allMatch(p -> p.joined() && p.connected() && p.wallsRemaining() == 5));
            }
            GameException error = assertThrows(GameException.class,
                    () -> server.join("Quinto", new RecordingCallback()));
            assertTrue(error.getMessage().contains("já começou"));
        }
    }

    @Test
    void lobbyRejectsActionsInvalidNamesAndDuplicateCallback() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            assertThrows(GameException.class, () -> server.join(" ", new RecordingCallback()));
            assertThrows(GameException.class, () -> server.join("Ana", null));
            assertThrows(GameException.class, () -> server.join("A\nB", new RecordingCallback()));
            RecordingCallback callback = new RecordingCallback();
            assertEquals(0, server.join(" Ana ", callback));
            assertThrows(GameException.class, () -> server.join("Ana de novo", callback));
            assertTrue(assertThrows(GameException.class, () -> server.move(0, 1, 4))
                    .getMessage().contains("Aguardando"));
            GameStateDTO lobby = callback.awaitState(state -> state.players().get(0).joined());
            assertFalse(lobby.started());
            assertNull(lobby.currentPlayerId());
            assertEquals("Ana", lobby.players().get(0).name());
            server.leave(0);
            assertEquals(0, server.join("Substituto", new RecordingCallback()));
            assertThrows(GameException.class, () -> server.leave(5));
        }
    }

    @Test
    void rejectsWrongTurnAndInvalidMoveThenAcceptsValidMoveAndWall() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            assertTrue(assertThrows(GameException.class, () -> server.move(1, 7, 4))
                    .getMessage().contains("Não é seu turno"));
            assertThrows(GameException.class, () -> server.move(0, 2, 4));
            assertThrows(GameException.class, () -> server.move(0, -1, 4));
            assertThrows(GameException.class, () -> server.move(4, 1, 4));
            server.move(0, 1, 4);
            GameStateDTO moved = callbacks[0].awaitState(state -> Integer.valueOf(1).equals(state.currentPlayerId()));
            assertEquals(1, moved.players().get(0).row());
            // Entradas têm versões 1 a 4; recusas não alteram a versão.
            assertEquals(5, moved.version());
            server.placeWall(1, 3, 3, WallOrientation.HORIZONTAL);
            GameStateDTO walled = callbacks[0].awaitState(state -> state.walls().size() == 1);
            assertEquals(4, walled.players().get(1).wallsRemaining());
            assertEquals(2, walled.currentPlayerId());
            assertEquals(WallOrientation.HORIZONTAL, walled.walls().get(0).orientation());
            assertThrows(GameException.class, () -> server.placeWall(2, 3, 3, WallOrientation.VERTICAL));
            assertThrows(GameException.class, () -> server.placeWall(2, 8, 0, WallOrientation.HORIZONTAL));
            assertThrows(GameException.class, () -> server.placeWall(2, 2, 2, null));
            server.move(2, 4, 7);
            assertEquals(7, callbacks[0].awaitState(state -> state.version() == 7).version());
        }
    }

    @Test
    void serverRejectsWallBlockingPathUsingModelBfs() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.placeWall(0, 0, 4, WallOrientation.HORIZONTAL);
            server.placeWall(1, 0, 3, WallOrientation.VERTICAL);
            assertTrue(assertThrows(GameException.class,
                    () -> server.placeWall(2, 0, 5, WallOrientation.VERTICAL))
                    .getMessage().contains("último caminho"));
            server.move(2, 4, 7);
            GameStateDTO state = callbacks[0].awaitState(dto -> dto.version() == 7);
            assertEquals(2, state.walls().size());
            assertEquals(5, state.players().get(2).wallsRemaining());
        }
    }

    @Test
    void leaveSkipsTurnAndRetainsPawnAndName() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.leave(0);
            GameStateDTO state = callbacks[1].awaitState(dto -> !dto.players().get(0).connected());
            assertEquals(1, state.currentPlayerId());
            assertEquals("Jogador 0", state.players().get(0).name());
            assertTrue(state.players().get(0).joined());
            assertEquals(0, state.players().get(0).row());
            assertEquals(4, state.players().get(0).column());
            assertThrows(GameException.class, () -> server.move(0, 1, 4));
            assertThrows(GameException.class, () -> server.leave(0));
            server.move(1, 7, 4);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"onMessage", "onStateUpdate"})
    void callbackFailureDisconnectsCurrentPlayerAndGameContinues(String method) throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            callbacks[1].failOn = method;
            server.move(0, 1, 4);
            GameStateDTO state = callbacks[2].awaitState(dto -> !dto.players().get(1).connected());
            assertEquals(2, state.currentPlayerId());
            assertEquals(8, state.players().get(1).row());
            server.move(2, 4, 7);
            callbacks[2].awaitState(dto -> Integer.valueOf(3).equals(dto.currentPlayerId()));
        }
    }

    @Test
    void callbackFailureDuringStartDoesNotPreventOtherStartNotifications() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback first = new RecordingCallback();
            first.failOn = "onGameStarted";
            server.join("Primeiro", first);
            server.join("Segundo", new RecordingCallback());
            server.join("Terceiro", new RecordingCallback());
            RecordingCallback fourth = new RecordingCallback();
            server.join("Quarto", fourth);
            GameStateDTO state = fourth.awaitState(dto -> dto.started() && !dto.players().get(0).connected());
            assertEquals(1, fourth.starts);
            assertEquals(1, state.currentPlayerId());
            server.move(1, 7, 4);
        }
    }

    @Test
    void failureInLobbyReleasesSlotBeforeStart() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback observer = new RecordingCallback();
            server.join("Observador", observer);
            RecordingCallback failed = new RecordingCallback();
            failed.failOn = "onStateUpdate";
            server.join("Falha", failed);
            observer.awaitState(dto -> dto.version() >= 3 && !dto.players().get(1).joined());
            assertEquals(1, server.join("Substituto", new RecordingCallback()));
        }
    }

    @Test
    void victoryNotifiesAllRemainingClientsAndRejectsFurtherMoves() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.leave(2);
            server.leave(3);
            // Mantém dois jogadores ativos para testar vitória por chegada ao objetivo.
            // Com apenas um, a nova regra já encerraria a partida por desconexão.
            server.move(0, 0, 3);
            server.move(1, 8, 5);
            for (int row = 1; row <= 8; row++) {
                server.move(0, row, 3);
                if (row < 8) { server.move(1, 8, row % 2 == 1 ? 4 : 5); }
            }
            callbacks[0].awaitWinner(0);
            GameStateDTO won = callbacks[0].awaitState(dto -> Integer.valueOf(0).equals(dto.winnerId()));
            assertNull(won.currentPlayerId());
            assertEquals(8, won.players().get(0).row());
            assertTrue(assertThrows(GameException.class, () -> server.move(0, 7, 3))
                    .getMessage().contains("já terminou"));
            assertThrows(GameException.class, () -> server.placeWall(0, 3, 3, WallOrientation.HORIZONTAL));
            // Uma vitória normal também permite reset e nova partida sem reiniciar a JVM.
            server.leave(0);
            server.leave(1);
            RecordingCallback[] next = joinFour(server);
            assertNull(next[0].awaitState(GameStateDTO::started).winnerId());
            server.move(0, 1, 4);
        }
    }

    @Test
    void threePlayersStayInLobbyAndReceiveCountOnEveryEntryAndExit() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback observer = new RecordingCallback();
            server.join("Primeiro", observer);
            observer.awaitMessage("Aguardando jogadores (1/4)");
            server.join("Segundo", new RecordingCallback());
            observer.awaitMessage("Aguardando jogadores (2/4)");
            server.join("Terceiro", new RecordingCallback());
            observer.awaitMessage("Aguardando jogadores (3/4)");
            GameStateDTO state = observer.awaitState(dto -> dto.version() == 3);
            assertFalse(state.started()); assertNull(state.currentPlayerId()); assertEquals(0, observer.starts);
            assertTrue(assertThrows(GameException.class, () -> server.move(0, 1, 4)).getMessage().contains("(3/4)"));
            assertTrue(assertThrows(GameException.class, () -> server.placeWall(0, 2, 2, WallOrientation.HORIZONTAL))
                    .getMessage().contains("(3/4)"));
            server.leave(1);
            GameStateDTO afterLeave = observer.awaitState(dto -> dto.version() == 4);
            assertFalse(afterLeave.started()); assertEquals(0, observer.starts);
            synchronized (observer) { assertTrue(observer.messages.get(observer.messages.size() - 1).contains("(2/4)")); }
        }
    }

    @Test
    void fifthPlayerIsClearlyRejectedWithoutDisruptingGame() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            assertTrue(assertThrows(GameException.class, () -> server.join("Quinto", new RecordingCallback()))
                    .getMessage().toLowerCase().contains("partida cheia"));
            server.move(0, 1, 4);
            assertEquals(1, callbacks[0].awaitState(dto -> dto.version() == 5).currentPlayerId());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyoneLeavingResetsLobbyAndAllGameData(boolean startFirstGame) throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback observer = new RecordingCallback();
            server.join("Antigo 0", observer);
            server.join("Antigo 1", new RecordingCallback());
            server.join("Antigo 2", new RecordingCallback());
            if (startFirstGame) {
                server.join("Antigo 3", new RecordingCallback());
                server.placeWall(0, 3, 0, WallOrientation.HORIZONTAL);
                server.move(1, 7, 4);
            }
            long previousVersion = startFirstGame ? 6 : 3;
            observer.awaitState(dto -> dto.version() == previousVersion);
            for (int id = 0; id < (startFirstGame ? 4 : 3); id++) { server.leave(id); }
            RecordingCallback[] next = joinFour(server);
            GameStateDTO fresh = next[0].awaitState(GameStateDTO::started);
            assertTrue(fresh.version() > previousVersion);
            assertTrue(fresh.walls().isEmpty()); assertNull(fresh.winnerId()); assertEquals(0, fresh.currentPlayerId());
            int[][] initial = {{0, 4}, {8, 4}, {4, 8}, {4, 0}};
            for (int id = 0; id < 4; id++) {
                var player = fresh.players().get(id);
                assertEquals(id, player.id()); assertEquals("Jogador " + id, player.name());
                assertEquals(5, player.wallsRemaining()); assertTrue(player.connected());
                assertEquals(initial[id][0], player.row()); assertEquals(initial[id][1], player.column());
            }
            server.move(0, 1, 4);
            next[0].awaitState(dto -> dto.players().get(0).row() == 1);
        }
    }

    @Test
    void threeDeparturesDeclareLastPlayerWinnerOnceWithReason() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.leave(1);
            assertNull(callbacks[0].awaitState(dto -> dto.version() == 5).winnerId());
            server.leave(2);
            assertNull(callbacks[0].awaitState(dto -> dto.version() == 6).winnerId());
            server.move(0, 1, 4); // Com dois conectados, a partida continua.
            assertEquals(3, callbacks[0].awaitState(dto -> dto.version() == 7).currentPlayerId());
            server.leave(3);
            callbacks[0].awaitWinner(0);
            callbacks[0].awaitMessage("demais jogadores desconectaram");
            GameStateDTO ended = callbacks[0].awaitState(dto -> dto.version() == 8);
            assertEquals(0, ended.winnerId()); assertNull(ended.currentPlayerId());
            synchronized (callbacks[0]) { assertEquals(List.of(0), callbacks[0].winners); }
            assertThrows(GameException.class, () -> server.move(0, 2, 4));
            assertThrows(GameException.class, () -> server.placeWall(0, 3, 3, WallOrientation.VERTICAL));
            server.leave(0);
            joinFour(server);
            server.move(0, 1, 4);
        }
    }

    @Test
    void callbackFailuresAlsoDeclareLastPlayerWinner() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            for (int id = 1; id < 4; id++) { callbacks[id].failOn = "onStateUpdate"; }
            server.move(0, 1, 4);
            callbacks[0].awaitWinner(0);
            callbacks[0].awaitMessage("demais jogadores desconectaram");
            GameStateDTO ended = callbacks[0].awaitState(dto -> dto.winnerId() != null);
            assertEquals(1, ended.players().stream().filter(GameStateDTO.PlayerDTO::connected).count());
        }
    }

    @Test
    void allCallbacksFailingResetServerForNewPlayers() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            for (RecordingCallback callback : callbacks) { callback.failOn = "onMessage"; }
            server.leave(0); // Notifica os demais, que também cairão nos callbacks.
            RecordingCallback fresh = new RecordingCallback();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (true) {
                try { assertEquals(0, server.join("Nova geração", fresh)); break; }
                catch (GameException stillDisconnecting) {
                    if (System.nanoTime() >= deadline) { throw stillDisconnecting; }
                    TimeUnit.MILLISECONDS.sleep(10);
                }
            }
            GameStateDTO lobby = fresh.awaitState(dto -> dto.players().get(0).joined());
            assertFalse(lobby.started()); assertNull(lobby.winnerId()); assertTrue(lobby.walls().isEmpty());
            for (int id = 1; id < 4; id++) { assertEquals(id, server.join("Novo " + id, new RecordingCallback())); }
            fresh.awaitState(GameStateDTO::started);
            server.move(0, 1, 4);
        }
    }

    @Test
    void leavingLobbyReusesSameIdAndSideWithoutStartingEarly() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback first = new RecordingCallback();
            server.join("Primeiro", first); server.join("Sai", new RecordingCallback()); server.join("Terceiro", new RecordingCallback());
            server.leave(1);
            assertEquals(1, server.join("Substituto", new RecordingCallback()));
            GameStateDTO lobby = first.awaitState(dto -> dto.version() == 5);
            assertFalse(lobby.started()); assertEquals("SOUTH", lobby.players().get(1).side());
            assertEquals("Substituto", lobby.players().get(1).name());
            assertEquals(8, lobby.players().get(1).row()); assertEquals(4, lobby.players().get(1).column());
            assertEquals(3, server.join("Quarto", new RecordingCallback()));
            first.awaitState(GameStateDTO::started); server.move(0, 1, 4);
        }
    }

    @Test
    void failureFromOldSessionCannotDisconnectNewGenerationAfterReset() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback delayed = new RecordingCallback() {
                @Override public void onMessage(String message) throws RemoteException {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    throw new RemoteException("Falha antiga depois do reset");
                }
            };
            server.join("Antigo", delayed);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int id = 1; id < 4; id++) { server.join("Antigo " + id, new RecordingCallback()); }
            for (int id = 0; id < 4; id++) { server.leave(id); }
            RecordingCallback fresh = new RecordingCallback();
            server.join("Novo", fresh);
            for (int id = 1; id < 4; id++) { server.join("Novo " + id, new RecordingCallback()); }
            release.countDown();
            GameStateDTO state = fresh.awaitState(GameStateDTO::started);
            assertTrue(state.players().stream().allMatch(GameStateDTO.PlayerDTO::connected));
            server.move(0, 1, 4);
            fresh.awaitState(dto -> dto.players().get(0).row() == 1);
        } finally { release.countDown(); }
    }

    @Test
    void concurrentDeparturesAndMoveProduceOnlyOneWinner() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.leave(1);
            CountDownLatch start = new CountDownLatch(1);
            Future<?> leaveTwo = pool.submit(() -> { start.await(); server.leave(2); return null; });
            Future<?> leaveThree = pool.submit(() -> { start.await(); server.leave(3); return null; });
            Future<?> move = pool.submit(() -> {
                start.await();
                try { server.move(0, 1, 4); } catch (GameException endedOrTurnChanged) { /* Recusa válida na corrida. */ }
                return null;
            });
            start.countDown();
            leaveTwo.get(5, TimeUnit.SECONDS); leaveThree.get(5, TimeUnit.SECONDS); move.get(5, TimeUnit.SECONDS);
            callbacks[0].awaitWinner(0);
            synchronized (callbacks[0]) { assertEquals(List.of(0), callbacks[0].winners); }
            assertThrows(GameException.class, () -> server.move(0, 2, 4));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void failureInGameOverDisconnectsClientWithoutLosingWinner() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            server.leave(2);
            server.leave(3);
            callbacks[1].failOn = "onGameOver";
            // Dois jogadores ativos: o sul alterna entre duas casas longe da coluna 3.
            server.move(0, 0, 3);
            server.move(1, 8, 5);
            for (int row = 1; row <= 8; row++) {
                server.move(0, row, 3);
                if (row < 8) { server.move(1, 8, row % 2 == 1 ? 4 : 5); }
            }
            callbacks[0].awaitWinner(0);
            GameStateDTO state = callbacks[0].awaitState(dto -> dto.winnerId() != null && !dto.players().get(1).connected());
            assertEquals(0, state.winnerId());
        }
    }

    @Test
    void simultaneousMovesCannotConsumeSameTurnTwice() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback[] callbacks = joinFour(server);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int column : new int[]{3, 5}) {
                results.add(pool.submit(() -> {
                    start.await();
                    try { server.move(0, 0, column); return true; }
                    catch (GameException rejected) { return false; }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) { if (result.get(5, TimeUnit.SECONDS)) { accepted++; } }
            assertEquals(1, accepted);
            assertEquals(5, callbacks[0].awaitState(dto -> dto.version() == 5).version());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void slowCallbackDoesNotHoldGameMonitor() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (GameServerImpl server = new GameServerImpl()) {
            RecordingCallback blocked = new RecordingCallback() {
                @Override
                public void onMessage(String message) throws RemoteException {
                    entered.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
            };
            server.join("Lento", blocked);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            // Estas operações precisam concluir mesmo com o callback ainda bloqueado.
            server.join("Dois", new RecordingCallback());
            server.join("Três", new RecordingCallback());
            server.join("Quatro", new RecordingCallback());
            server.move(0, 1, 4);
        } finally {
            release.countDown();
        }
    }

    @Test
    void dtoIsSerializableAndItsListsAreImmutable() throws Exception {
        try (GameServerImpl server = new GameServerImpl()) {
            GameStateDTO original = joinFour(server)[0].awaitState(GameStateDTO::started);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(original); }
            try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                GameStateDTO copy = (GameStateDTO) input.readObject();
                assertEquals(original, copy);
                assertThrows(UnsupportedOperationException.class, () -> copy.players().clear());
                assertThrows(UnsupportedOperationException.class, () -> copy.walls().clear());
            }
        }
    }

    @Test
    void exportedServiceRegistryAndCallbacksWorkThroughRealRmi() throws Exception {
        System.setProperty("java.rmi.server.hostname", "localhost");
        Registry registry = LocateRegistry.createRegistry(0); // Porta livre escolhida pelo RMI nos testes.
        RecordingCallback[] local = new RecordingCallback[4];
        try (GameServerImpl server = new GameServerImpl()) {
            try {
                GameService stub = (GameService) UnicastRemoteObject.exportObject(server, 0);
                registry.rebind(GameService.SERVICE_NAME, stub);
                // Usar o stub do Registry força uma consulta remota, sem acesso direto ao objeto.
                Registry remoteRegistry = (Registry) RemoteObject.toStub(registry);
                GameService service = (GameService) remoteRegistry.lookup(GameService.SERVICE_NAME);
                for (int id = 0; id < 4; id++) {
                    local[id] = new RecordingCallback();
                    ClientCallback callback = (ClientCallback) UnicastRemoteObject.exportObject(local[id], 0);
                    assertEquals(id, service.join("Remoto " + id, callback));
                }
                local[0].awaitState(GameStateDTO::started);
                assertThrows(GameException.class, () -> service.move(1, 7, 4));
                service.move(0, 1, 4);
                local[0].awaitState(dto -> dto.players().get(0).row() == 1);
                // Simula a queda real do objeto remoto, e não uma exceção local de teste.
                UnicastRemoteObject.unexportObject(local[1], true);
                service.move(1, 7, 4);
                local[0].awaitState(dto -> !dto.players().get(1).connected());
                service.placeWall(2, 3, 3, WallOrientation.VERTICAL);
                local[0].awaitState(dto -> dto.walls().size() == 1);
                service.leave(3);
                local[0].awaitState(dto -> !dto.players().get(3).connected());
            } finally {
                for (RecordingCallback callback : local) {
                    if (callback != null) {
                        try { UnicastRemoteObject.unexportObject(callback, true); }
                        catch (java.rmi.NoSuchObjectException alreadyUnexported) { /* Já removido pelo teste. */ }
                    }
                }
                try { UnicastRemoteObject.unexportObject(server, true); }
                catch (java.rmi.NoSuchObjectException notExported) { /* Falha antes da exportação. */ }
            }
        } finally {
            UnicastRemoteObject.unexportObject(registry, true);
        }
    }
}
