package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** Contrato RMI: o cliente envia intenções e o servidor decide se são válidas. */
public interface GameService extends Remote {
    String SERVICE_NAME = "QuoridorService";

    /** Retorna um ID de 0 a 3; a quarta entrada inicia a partida automaticamente. */
    int join(String nome, ClientCallback callback) throws RemoteException, GameException;

    /** Destino absoluto, com linha e coluna de 0 a 8, incluindo destinos de saltos. */
    void move(int playerId, int linha, int coluna) throws RemoteException, GameException;

    /** Ancoragem da parede, com linha e coluna de 0 a 7. */
    void placeWall(int playerId, int linha, int coluna, WallOrientation orientation)
            throws RemoteException, GameException;

    void leave(int playerId) throws RemoteException, GameException;
}
