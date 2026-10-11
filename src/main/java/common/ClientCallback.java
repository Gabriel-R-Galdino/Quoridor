package common;

import java.rmi.Remote;
import java.rmi.RemoteException;

/** Cada cliente exporta este contrato para receber chamadas do servidor via RMI. */
public interface ClientCallback extends Remote {
    void onGameStarted() throws RemoteException;
    void onStateUpdate(GameStateDTO state) throws RemoteException;
    /** Vencedor é o ID de 0 a 3, cujo nome também está no DTO. */
    void onGameOver(int vencedor) throws RemoteException;
    void onMessage(String message) throws RemoteException;
}
