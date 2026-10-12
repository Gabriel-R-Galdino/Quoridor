# Roteiro de apresentação — Quoridor distribuído

Duração sugerida: 10 a 15 minutos, incluindo uma demonstração. Os comandos completos estão no [README](README.md).

## 1. Objetivo e divisão em processos

**Fala sugerida:** “Implementei um Quoridor para quatro jogadores. Cada jogador executa seu próprio cliente em uma JVM, e outra JVM executa o servidor. Todos estão em localhost. O servidor é autoritativo: os clientes enviam intenções, e somente o servidor aceita e aplica as jogadas.”

Mostre os cinco terminais e os quatro pacotes:

| Pacote | O que mostrar |
| --- | --- |
| `model` | Regras e estado local, sem dependência de RMI. |
| `common` | Contratos remotos, exceção de negócio e DTOs serializáveis. |
| `server` | Publicação do serviço, sincronização e notificações. |
| `client` | Consulta ao Registry, callback exportado e interface textual. |

O Registry embutido pertence à JVM do servidor; não existe um sexto processo `rmiregistry`.

## 2. Onde está o RMI no código

Abra os arquivos abaixo e aponte estas classes e métodos:

| Arquivo e classe | Trecho para explicar |
| --- | --- |
| [GameService.java](src/main/java/common/GameService.java), interface `GameService` | `extends Remote`; `join`, `move`, `placeWall` e `leave` declaram `RemoteException` e `GameException`. |
| [ClientCallback.java](src/main/java/common/ClientCallback.java), interface `ClientCallback` | Outro contrato `Remote`: `onGameStarted`, `onStateUpdate`, `onGameOver` e `onMessage`. |
| [ServerMain.java](src/main/java/server/ServerMain.java), classe `ServerMain` | `LocateRegistry.createRegistry(1099)`, `UnicastRemoteObject.exportObject(service, 0)` e `registry.rebind`. |
| [ClientMain.java](src/main/java/client/ClientMain.java), classe `ClientMain` | `LocateRegistry.getRegistry(host, 1099)`, `registry.lookup`, exportação do callback e `service.join(name, stub)`. |
| [GameServerImpl.java](src/main/java/server/GameServerImpl.java), classe `GameServerImpl` | Implementa `GameService`; `publish` chama os métodos do callback armazenado para cada jogador. |
| [GameStateDTO.java](src/main/java/common/GameStateDTO.java), record `GameStateDTO` | Implementa `Serializable`; leva posições, paredes, turno, vencedor e situação dos jogadores. |

**Fala sugerida:** “Não criei conexões nem protocolo com Socket. Uso os contratos e a infraestrutura do Java RMI. O JDK cuida do transporte das invocações e da serialização.”

## 3. Registry, stub e callback

**Registry:** é o catálogo que associa um nome a uma referência remota. O servidor registra `QuoridorService`. O cliente consulta esse nome na porta 1099. Depois da consulta, as jogadas usam a referência do serviço; o Registry não valida jogadas e não intermedeia cada chamada.

**Stub:** é o representante local de um objeto remoto. Quando o cliente chama `service.move(...)`, ele chama o stub, que encaminha a invocação à JVM do servidor. `exportObject` produz a referência remota usada para acessar o objeto exportado. Não precisei gerar classes de stub com `rmic`.

**Callback:** inverte o sentido de uma chamada. Cada cliente exporta `ClientCallbackImpl` e passa seu stub em `join`. O servidor guarda esse stub e chama `onStateUpdate` para avisar mudanças sem exigir consultas periódicas do cliente.

**Dados por valor e referências remotas:** o `GameStateDTO` é uma cópia serializável do estado público. O callback é uma referência a um objeto remoto, não uma cópia da interface do terminal. O estado mutável do pacote `model` permanece no servidor.

A porta 1099 é do Registry. A porta 0 em `exportObject` pede ao RMI que escolha uma porta para o objeto remoto. Servidor e callbacks anunciam `localhost`, conforme o requisito de executar tudo na mesma máquina.

## 4. Caminho de uma jogada

Use `m s` do jogador A na posição inicial como exemplo:

```text
TerminalUI.handleCommand
  -> TerminalCommand.Move.destination: pedido para (1,4)
  -> stub de GameService.move(0,1,4)
  -> RMI entrega a chamada a GameServerImpl.move
  -> requireTurn verifica registro, início, vencedor e turno
  -> QuoridorRules.tryMove verifica o movimento
  -> estado oficial é alterado; turno passa ao jogador B
  -> publish captura um GameStateDTO e enfileira notificações
  -> stubs de ClientCallback levam onStateUpdate aos clientes
  -> ClientCallbackImpl coloca eventos na fila da TerminalUI
  -> TerminalUI.renderAscii desenha o estado recebido
```

O método remoto retorna após enfileirar as notificações. Os callbacks são assíncronos em relação a esse retorno. Se a jogada for inválida, o servidor lança `GameException`; `TerminalUI.handleCommand` exibe a mensagem e mantém o cliente em execução. `RemoteException` representa uma falha de comunicação, não uma recusa das regras.

## 5. Sincronização e concorrência

Em [GameServerImpl.java](src/main/java/server/GameServerImpl.java), mostre que `join`, `move`, `placeWall` e `leave` são `synchronized`. O monitor é **a instância do serviço**, e todo acesso ao estado do jogo acontece sob esse monitor. Assim, duas chamadas não validam e aplicam jogadas simultaneamente sobre o mesmo turno.

**Fala sugerida:** “O RMI pode atender clientes em threads diferentes. Eu protejo a operação completa: validar, alterar o estado, atualizar o turno e capturar o snapshot. Não basta sincronizar só uma lista de jogadores.”

Mostre `publish`, `notifications`, `isActive` e `callbackFailed`:

- Sob o monitor, `publish` captura um DTO imutável e os destinatários.
- Um `ExecutorService` de uma thread executa as notificações em ordem.
- As chamadas remotas dos callbacks acontecem fora do monitor.
- `isActive` e `callbackFailed` entram brevemente no monitor para consultar ou alterar sessões.
- A identidade da sessão é conferida para que uma falha antiga não desconecte um novo ocupante da mesma vaga no lobby.

Um callback lento pode atrasar a fila de notificações, mas não mantém o monitor do estado bloqueado. O teste `slowCallbackDoesNotHoldGameMonitor`, em [GameServerImplTest.java](src/test/java/server/GameServerImplTest.java), demonstra isso.

No cliente, [TerminalUI.java](src/main/java/client/TerminalUI.java) usa uma `BlockingQueue`: callbacks e teclado colocam eventos na fila, e um único laço processa o estado e escreve no terminal. A thread do teclado é daemon. O cliente ignora versões antigas de DTOs e aguarda uma atualização depois de um pedido aceito, evitando repetir comandos sobre o mesmo estado.

## 6. Regras e BFS

Abra [QuoridorRules.java](src/main/java/model/QuoridorRules.java): `tryMove`, `legalMove`, `tryPlaceWall` e `hasPath`. Mostre também [Wall.java](src/main/java/model/Wall.java), método `conflictsWith`, e [Board.java](src/main/java/model/Board.java), método `canStep`.

Explique: tabuleiro 9x9, quatro origens, cinco paredes por jogador, objetivo no lado oposto, movimento ortogonal, salto reto e diagonais quando ele está impedido. Não há salto sobre uma cadeia de peões.

**Fala sugerida:** “Antes de aceitar uma parede, faço uma busca em largura para cada jogador. A busca considera as passagens bloqueadas e uma parede candidata, sem modificar o estado. Se qualquer objetivo ficar inacessível, rejeito a parede. Peões são ignorados na busca de caminho estrutural, mas são obstáculos nas jogadas.”

Todos os quatro jogadores participam da BFS, mesmo desconectados. Uma recusa não gasta parede nem consome turno. O vencedor encerra a aceitação de novas jogadas.

## 7. Como tratei desconexões

Mostre estes caminhos:

1. **Saída voluntária:** `q` encerra o laço do terminal. `ClientMain.cleanup` chama `leave` e remove a exportação do callback. O fechamento da entrada e o hook de Ctrl+C usam a mesma limpeza, protegida contra execução duplicada.
2. **Falha do callback:** `GameServerImpl.publish` captura `RemoteException` ou falha de execução; `callbackFailed` confirma a sessão e chama `disconnect` sob o monitor.
3. **Efeito no jogo:** `disconnect` chama `QuoridorRules.markDisconnected`; [GameState.java](src/main/java/model/GameState.java), método `advanceTurn`, pula jogadores desconectados. O peão permanece na casa como obstáculo.
4. **Aviso aos demais:** uma nova versão do DTO é publicada, preservando o nome e indicando `connected = false` durante a partida.

Antes do início, a vaga desconectada fica disponível. Depois do início, esta versão não admite novos jogadores ou reconexão. Uma queda é detectada ao tentar um callback; não há heartbeat. Se todos saírem, não há novo turno ativo. Se o servidor cair, o cliente apresenta o erro de comunicação e continua permitindo `q`.

## 8. Demonstração curta e reproduzível

Compile antes de iniciar o servidor. Abra um terminal para o servidor e quatro para clientes, registrando os nomes nesta ordem: Ana, Bruno, Carla e Davi.

1. Mostre que o jogo só inicia na quarta entrada.
2. Ana (A): `m s`. O turno passa a Bruno (B).
3. Bruno: `p 3 0 h`. Mostre a parede e o inventário reduzido para quatro.
4. Carla (C): `m a`. O turno passa a Davi (D).
5. Davi: `q`. Mostre o log de desconexão, o peão D preservado e o turno de Ana.
6. Ana: `p 3 0 h`. A sobreposição é recusada pelo servidor; a aplicação e o turno permanecem ativos.
7. Ana: `m s`. A jogada é aceita, demonstrando a continuidade após o erro.
8. Encerre os demais clientes com `q` e o servidor com Ctrl+C.

Para demonstrar saltos, fechamento de caminho e vitória sem uma partida longa, abra os cenários de [QuoridorRulesTest.java](src/test/java/model/QuoridorRulesTest.java). Mostre também [ClientProcessIntegrationTest.java](src/test/java/client/ClientProcessIntegrationTest.java): o teste inicia cinco processos reais e executa a sequência de comunicação.

## 9. Evidências para concluir

- `mvn test` executa os testes de regras, serviço e terminal, incluindo integração RMI e cinco JVMs separadas. Deixe a porta 1099 livre.
- As interfaces remotas e exportações estão explicitamente nos arquivos indicados acima.
- A revisão dos fontes de aplicação e testes não encontrou importação, instanciação ou referência direta a `java.net.Socket` ou `java.net.ServerSocket`.
- O transporte de rede interno do RMI pertence ao JDK; a aplicação usa somente a API RMI.

**Fala de encerramento:** “A separação principal é entre intenção, comunicação e regra. O cliente apresenta e pede; o RMI transporta; o servidor sincroniza e valida; os callbacks distribuem cópias do resultado.”
