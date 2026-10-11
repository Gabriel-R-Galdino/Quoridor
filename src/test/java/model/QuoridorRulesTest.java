package model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static model.Wall.Orientation.HORIZONTAL;
import static model.Wall.Orientation.VERTICAL;
import static org.junit.jupiter.api.Assertions.*;

/** Cenários locais: nenhum teste precisa de servidor, RMI ou entrada de terminal. */
class QuoridorRulesTest {
    // A preparação usa operações internas do pacote para isolar uma regra por teste.
    // A jogada sob teste sempre passa pela API pública do motor.
    private static void position(GameState state, int id, int row, int column) {
        state.replacePlayer(state.getPlayer(id).moveTo(new Position(row, column)));
    }

    private static GameState adjacentPawns() {
        GameState state = new GameState();
        position(state, 0, 4, 4);
        position(state, 1, 5, 4);
        return state;
    }

    @Test
    void initialStateHasFourSidesAndFiveWalls() {
        QuoridorRules rules = new QuoridorRules();
        assertEquals(new Position(0, 4), rules.getState().getPlayer(0).position());
        assertEquals(new Position(8, 4), rules.getState().getPlayer(1).position());
        assertEquals(new Position(4, 8), rules.getState().getPlayer(2).position());
        assertEquals(new Position(4, 0), rules.getState().getPlayer(3).position());
        assertTrue(rules.getState().getPlayers().stream().allMatch(p -> p.wallsRemaining() == 5));
        assertEquals(0, rules.currentPlayer().id());
        assertNull(rules.getWinner());
    }

    @Test
    void jumpsOverOneAdjacentPawn() {
        QuoridorRules rules = new QuoridorRules(adjacentPawns());
        assertTrue(rules.tryMove(0, 6, 4));
        assertEquals(new Position(6, 4), rules.getState().getPlayer(0).position());
        assertEquals(1, rules.currentPlayer().id());
    }

    @Test
    void diagonalIsForbiddenWhenStraightJumpIsAvailable() {
        QuoridorRules rules = new QuoridorRules(adjacentPawns());
        assertFalse(rules.tryMove(0, 5, 3));
        assertFalse(rules.tryMove(0, 5, 5));
        assertEquals(new Position(4, 4), rules.getState().getPlayer(0).position());
        assertEquals(0, rules.currentPlayer().id());
    }

    @Test
    void diagonalJumpIsAllowedOnBothSidesOfWallBehindPawn() {
        for (int column : new int[]{3, 5}) {
            GameState state = adjacentPawns();
            state.getBoard().addWall(new Wall(5, 4, HORIZONTAL));
            QuoridorRules rules = new QuoridorRules(state);
            assertFalse(rules.tryMove(0, 6, 4));
            assertTrue(rules.tryMove(0, 5, column));
        }
    }

    @Test
    void anotherPawnBehindAllowsDiagonalButNeverChainJump() {
        GameState state = adjacentPawns();
        position(state, 2, 6, 4);
        QuoridorRules rules = new QuoridorRules(state);
        assertFalse(rules.tryMove(0, 6, 4));
        assertFalse(rules.tryMove(0, 7, 4));
        assertTrue(rules.tryMove(0, 5, 5));
    }

    @Test
    void boardEdgeAlsoAllowsDiagonalJump() {
        GameState state = new GameState();
        position(state, 0, 7, 4);
        QuoridorRules rules = new QuoridorRules(state);
        assertTrue(rules.tryMove(0, 8, 3));
        assertEquals(0, rules.getWinner().id());
    }

    @Test
    void wallBetweenPawnsPreventsJump() {
        GameState state = adjacentPawns();
        state.getBoard().addWall(new Wall(4, 4, HORIZONTAL));
        QuoridorRules rules = new QuoridorRules(state);
        assertFalse(rules.tryMove(0, 6, 4));
        assertFalse(rules.tryMove(0, 5, 3));
    }

    @Test
    void diagonalCannotCrossSideWallOrLandOnPawn() {
        GameState state = adjacentPawns();
        state.getBoard().addWall(new Wall(5, 4, HORIZONTAL));
        state.getBoard().addWall(new Wall(4, 3, VERTICAL));
        position(state, 2, 5, 5);
        QuoridorRules rules = new QuoridorRules(state);
        assertFalse(rules.tryMove(0, 5, 3));
        assertFalse(rules.tryMove(0, 5, 5));
    }

    @Test
    void ordinaryDiagonalAndOccupiedDestinationAreRejected() {
        QuoridorRules rules = new QuoridorRules(adjacentPawns());
        assertFalse(rules.tryMove(0, 3, 3));
        assertFalse(rules.tryMove(0, 5, 4));
        assertFalse(rules.tryMove(0, 4, 4));
    }

    @Test
    void rejectsCompleteAndPartialWallOverlap() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 3, 3, HORIZONTAL));
        assertFalse(rules.tryPlaceWall(1, 3, 3, HORIZONTAL));
        assertFalse(rules.tryPlaceWall(1, 3, 2, HORIZONTAL));
        assertFalse(rules.tryPlaceWall(1, 3, 4, HORIZONTAL));
        assertEquals(1, rules.getState().getBoard().getWalls().size());
        assertEquals(5, rules.getState().getPlayer(1).wallsRemaining());
        assertEquals(1, rules.currentPlayer().id());
    }

    @Test
    void rejectsVerticalOverlapAndWallCrossing() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 3, 3, VERTICAL));
        assertFalse(rules.tryPlaceWall(1, 2, 3, VERTICAL));
        assertFalse(rules.tryPlaceWall(1, 4, 3, VERTICAL));
        assertFalse(rules.tryPlaceWall(1, 3, 3, HORIZONTAL));
    }

    @Test
    void allowsWallsTouchingAtEndpoints() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 3, 2, HORIZONTAL));
        assertTrue(rules.tryPlaceWall(1, 3, 4, HORIZONTAL));
    }

    @Test
    void rejectsWallRemovingLastPathWithoutChangingState() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 0, 4, HORIZONTAL));
        assertTrue(rules.tryPlaceWall(1, 0, 3, VERTICAL));
        // A parede à direita fecharia as duas casas no topo onde está o jogador 0.
        String before = rules.renderAscii();
        assertFalse(rules.tryPlaceWall(2, 0, 5, VERTICAL));
        assertEquals(before, rules.renderAscii());
    }

    @Test
    void checksPathOfOtherPlayerEvenWhenDisconnected() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 7, 4, HORIZONTAL));
        assertTrue(rules.tryPlaceWall(1, 7, 3, VERTICAL));
        assertTrue(rules.markDisconnected(1));
        // O jogador 2 não pode fechar o caminho do jogador 1, embora ele esteja offline.
        assertFalse(rules.tryPlaceWall(2, 7, 5, VERTICAL));
        assertEquals(2, rules.currentPlayer().id());
    }

    @ParameterizedTest
    @EnumSource(Player.Side.class)
    void detectsVictoryOnEveryOppositeSide(Player.Side side) {
        GameState state = new GameState();
        // Usa o jogador da vez com cada objetivo para isolar a detecção de vitória.
        Position start = switch (side) {
            case NORTH -> new Position(7, 3);
            case SOUTH -> new Position(1, 3);
            case EAST -> new Position(3, 1);
            case WEST -> new Position(3, 7);
        };
        Position target = switch (side) {
            case NORTH -> new Position(8, 3);
            case SOUTH -> new Position(0, 3);
            case EAST -> new Position(3, 0);
            case WEST -> new Position(3, 8);
        };
        state.replacePlayer(new Player(0, side, start, 5, true));
        QuoridorRules rules = new QuoridorRules(state);
        assertTrue(rules.tryMove(0, target.row(), target.column()));
        assertEquals(0, rules.getWinner().id());
        assertFalse(rules.tryMove(0, start.row(), start.column()));
        assertFalse(rules.tryPlaceWall(0, 2, 2, HORIZONTAL));
        assertFalse(rules.tryMove(1, 7, 4));
    }

    @Test
    void fixedTurnOrderAndInvalidRequestsDoNotConsumeTurn() {
        QuoridorRules rules = new QuoridorRules();
        assertFalse(rules.tryMove(1, 7, 4));
        assertFalse(rules.tryMove(-1, 1, 4));
        assertFalse(rules.tryMove(0, -1, 4));
        assertFalse(rules.tryMove(0, 2, 4));
        assertTrue(rules.tryMove(0, 1, 4));
        assertTrue(rules.tryMove(1, 7, 4));
        assertTrue(rules.tryMove(2, 4, 7));
        assertTrue(rules.tryMove(3, 4, 1));
        assertEquals(0, rules.currentPlayer().id());
    }

    @Test
    void disconnectSkipsTurnsAndLeavesPawnAsObstacle() {
        QuoridorRules rules = new QuoridorRules(adjacentPawns());
        assertTrue(rules.markDisconnected(1));
        assertFalse(rules.tryMove(0, 5, 4));
        assertTrue(rules.tryMove(0, 6, 4));
        assertEquals(2, rules.currentPlayer().id());
        assertEquals(new Position(5, 4), rules.getState().getPlayer(1).position());
        assertTrue(rules.markDisconnected(2));
        assertEquals(3, rules.currentPlayer().id());
    }

    @Test
    void noConnectedPlayersMeansNoTurnAndNoActions() {
        QuoridorRules rules = new QuoridorRules();
        for (int id = 0; id < 4; id++) { assertTrue(rules.markDisconnected(id)); }
        assertNull(rules.currentPlayer());
        assertNull(rules.getWinner());
        assertFalse(rules.tryMove(0, 1, 4));
        assertFalse(rules.tryPlaceWall(0, 3, 3, HORIZONTAL));
        assertFalse(rules.markDisconnected(0));
        assertFalse(rules.markDisconnected(4));
    }

    @Test
    void wallInventoryAndInvalidAnchorsAreEnforced() {
        GameState state = new GameState();
        state.replacePlayer(new Player(0, Player.Side.NORTH, new Position(0, 4), 0, true));
        QuoridorRules rules = new QuoridorRules(state);
        assertFalse(rules.tryPlaceWall(0, 3, 3, HORIZONTAL));
        assertTrue(rules.tryMove(0, 1, 4));
        assertFalse(rules.tryPlaceWall(1, 8, 3, HORIZONTAL));
        assertFalse(rules.tryPlaceWall(1, 3, -1, VERTICAL));
        assertFalse(rules.tryPlaceWall(1, 3, 3, null));
    }

    @Test
    void wallsBlockBothSegmentsInBothDirections() {
        Board board = new Board();
        board.addWall(new Wall(2, 2, HORIZONTAL));
        board.addWall(new Wall(5, 5, VERTICAL));
        for (int column : new int[]{2, 3}) {
            assertFalse(board.canStep(2, column, 3, column));
            assertFalse(board.canStep(3, column, 2, column));
        }
        for (int row : new int[]{5, 6}) {
            assertFalse(board.canStep(row, 5, row, 6));
            assertFalse(board.canStep(row, 6, row, 5));
        }
        assertTrue(board.canStep(2, 4, 3, 4));
        assertTrue(board.canStep(7, 5, 7, 6));
    }

    @Test
    void asciiShowsPawnsWallsTurnAndDisconnection() {
        QuoridorRules rules = new QuoridorRules();
        assertTrue(rules.tryPlaceWall(0, 2, 2, HORIZONTAL));
        assertTrue(rules.tryPlaceWall(1, 5, 5, VERTICAL));
        rules.markDisconnected(3);
        String ascii = rules.renderAscii();
        assertTrue(ascii.contains("-"));
        assertTrue(ascii.contains("|"));
        assertTrue(ascii.contains("Turno: jogador 3"));
        assertTrue(ascii.contains("[desconectado]"));
        assertTrue(ascii.contains("Jogador 1 (NORTH): 4 paredes"));
        assertTrue(ascii.contains("0   . . . . 1 . . . ."));
    }

    @Test
    void publicCollectionsCannotModifyState() {
        QuoridorRules rules = new QuoridorRules();
        assertThrows(UnsupportedOperationException.class, () -> rules.getState().getPlayers().clear());
        assertThrows(UnsupportedOperationException.class, () -> rules.getState().getBoard().getWalls().clear());
    }
}
