package client.gui;

import common.GameStateDTO;
import common.WallOrientation;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** As dicas são verificadas sem tocar no motor nem alterar contratos remotos. */
class BoardHintsTest {
    static GameStateDTO initial() {
        return new GameStateDTO(4, 9, true, 0, null, List.of(
                new GameStateDTO.PlayerDTO(0, "Ana", "NORTH", 0, 4, 5, true, true),
                new GameStateDTO.PlayerDTO(1, "Bruno", "SOUTH", 8, 4, 5, true, true),
                new GameStateDTO.PlayerDTO(2, "Carla", "EAST", 4, 8, 5, true, true),
                new GameStateDTO.PlayerDTO(3, "Davi", "WEST", 4, 0, 5, true, true)), List.of());
    }
    static GameStateDTO withWalls(GameStateDTO state, GameStateDTO.WallDTO... walls) {
        return new GameStateDTO(state.version(), 9, true, 0, null, state.players(), List.of(walls));
    }
    static GameStateDTO positioned(int northRow, int southRow, boolean southConnected) {
        var players = new ArrayList<>(initial().players());
        players.set(0, new GameStateDTO.PlayerDTO(0, "Ana", "NORTH", northRow, 4, 5, true, true));
        players.set(1, new GameStateDTO.PlayerDTO(1, "Bruno", "SOUTH", southRow, 4, 5, true, southConnected));
        return new GameStateDTO(4, 9, true, 0, null, players, List.of());
    }
    @Test void initialMovesAndBoundaries() {
        assertEquals(Set.of(new BoardHints.Cell(0, 3), new BoardHints.Cell(0, 5), new BoardHints.Cell(1, 4)), new BoardHints(initial()).moves(0));
    }
    @Test void straightJumpIncludesDisconnectedPawnAndExcludesDiagonal() {
        var moves = new BoardHints(positioned(4, 5, false)).moves(0);
        assertTrue(moves.contains(new BoardHints.Cell(6, 4)));
        assertFalse(moves.contains(new BoardHints.Cell(5, 4)));
        assertFalse(moves.contains(new BoardHints.Cell(5, 3)));
    }
    @Test void diagonalsAroundWallAndBoardEdge() {
        var state = withWalls(positioned(4, 5, true), new GameStateDTO.WallDTO(5, 4, WallOrientation.HORIZONTAL));
        var moves = new BoardHints(state).moves(0);
        assertTrue(moves.contains(new BoardHints.Cell(5, 3)));
        assertTrue(moves.contains(new BoardHints.Cell(5, 5)));
        assertFalse(moves.contains(new BoardHints.Cell(6, 4)));
        assertTrue(new BoardHints(positioned(7, 8, true)).moves(0).contains(new BoardHints.Cell(8, 3)));
    }
    @Test void cannotJumpThroughWallBetweenPawns() {
        var state = withWalls(positioned(4, 5, true), new GameStateDTO.WallDTO(4, 4, WallOrientation.HORIZONTAL));
        var moves = new BoardHints(state).moves(0);
        assertFalse(moves.contains(new BoardHints.Cell(6, 4)));
        assertFalse(moves.contains(new BoardHints.Cell(5, 5)));
    }
    @Test void secondPawnBehindAllowsDiagonalWithoutChainJump() {
        var state = positioned(4, 5, true);
        var players = new ArrayList<>(state.players());
        players.set(2, new GameStateDTO.PlayerDTO(2, "Carla", "EAST", 6, 4, 5, true, true));
        var moves = new BoardHints(new GameStateDTO(4, 9, true, 0, null, players, List.of())).moves(0);
        assertTrue(moves.contains(new BoardHints.Cell(5, 3)));
        assertFalse(moves.contains(new BoardHints.Cell(7, 4)));
    }
    @Test void wallOverlapCrossingAndEndpoints() {
        var hints = new BoardHints(withWalls(initial(), new GameStateDTO.WallDTO(3, 3, WallOrientation.HORIZONTAL)));
        assertFalse(hints.wallValid(0, new GameStateDTO.WallDTO(3, 3, WallOrientation.HORIZONTAL)));
        assertFalse(hints.wallValid(0, new GameStateDTO.WallDTO(3, 4, WallOrientation.HORIZONTAL)));
        assertFalse(hints.wallValid(0, new GameStateDTO.WallDTO(3, 3, WallOrientation.VERTICAL)));
        assertTrue(hints.wallValid(0, new GameStateDTO.WallDTO(3, 5, WallOrientation.HORIZONTAL)));
    }
    @Test void bfsRejectsClosingLastPathWithoutChangingDto() {
        var state = withWalls(initial(), new GameStateDTO.WallDTO(0, 4, WallOrientation.HORIZONTAL),
                new GameStateDTO.WallDTO(0, 3, WallOrientation.VERTICAL));
        assertFalse(new BoardHints(state).wallValid(2, new GameStateDTO.WallDTO(0, 5, WallOrientation.VERTICAL)));
        assertEquals(2, state.walls().size());
    }
    @Test void bfsAlsoProtectsDisconnectedPlayer() {
        var state = withWalls(positioned(0, 8, false), new GameStateDTO.WallDTO(7, 4, WallOrientation.HORIZONTAL),
                new GameStateDTO.WallDTO(7, 3, WallOrientation.VERTICAL));
        assertFalse(new BoardHints(state).wallValid(0, new GameStateDTO.WallDTO(7, 5, WallOrientation.VERTICAL)));
    }
    @Test void inventoryAndOutsideAnchorsAreInvalid() {
        var players = new ArrayList<>(initial().players());
        players.set(0, new GameStateDTO.PlayerDTO(0, "Ana", "NORTH", 0, 4, 0, true, true));
        var hints = new BoardHints(new GameStateDTO(4, 9, true, 0, null, players, List.of()));
        assertFalse(hints.wallValid(0, new GameStateDTO.WallDTO(2, 2, WallOrientation.VERTICAL)));
        assertFalse(new BoardHints(initial()).wallValid(0, new GameStateDTO.WallDTO(8, 0, WallOrientation.HORIZONTAL)));
    }
}
