package client;

import common.ClientCallback;
import common.GameStateDTO;

/** Receberá as atualizações enviadas pelo servidor por chamadas RMI. */
public class ClientCallbackImpl implements ClientCallback {
    // A interface do cliente ainda será implementada; os métodos cumprem o contrato.
    @Override
    public void onGameStarted() {
        // A implementar: indicar que a partida começou.
    }

    @Override
    public void onStateUpdate(GameStateDTO state) {
        // A implementar: encaminhar o DTO à interface, coordenando com o teclado.
    }

    @Override
    public void onGameOver(int vencedor) {
        // A implementar: apresentar o vencedor.
    }

    @Override
    public void onMessage(String message) {
        // A implementar: apresentar mensagens do servidor.
    }
}
