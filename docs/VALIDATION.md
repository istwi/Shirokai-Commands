# Validação — 1.0.0+1.21.1

Verificação local em 10/11/2026. Código e jar de testes enviados à branch `main`; não há tag ou release publicada.

## Resultados executados

| Verificação | Resultado |
| --- | --- |
| JDK 21, Loom 1.8.13, Minecraft 1.21.1, Yarn build.3 | Compilação realizada |
| `./gradlew build` | Sucesso; jar remapeado e fontes gerados |
| Testes JUnit | 118 testes, zero falhas/erros/skips |
| `python3 scripts/integration-test.py` | Servidor Fabric dedicado: 96 assertions aprovadas |
| `./gradlew runServer --args nogui` | Inicialização normal atingiu `Done`, sem mod auxiliar de teste; encerramento limpo |
| Metadados do jar | Ambiente `server`; mod auxiliar de teste ausente |
| Checksums Gradle e JDK | Conferidos antes de usar os arquivos baixados |
| GitHub Actions | Arquivos criados; execução remota ainda não realizada |

Os 118 testes JUnit abrangem: modos HIDE/BLOCK/REPLACE, ALLOW e prioridade de segurança, bypass, whitelist, permissões de regra/alias, namespaces, limites de palavras, ciclos/duplicações, JSON inválido, backup, auditoria sem argumentos sensíveis, filtragem Brigadier e contrato de consulta da API LuckPerms.

Os seis testes da API LuckPerms usam interfaces oficiais com proxies controlados: TRUE, FALSE, UNDEFINED, usuário não carregado, QueryOptions contextual e fallback estático. **Não comprovam execução com o plugin LuckPerms instalado**, nem grupos/herança do plugin real.

As 96 assertions dedicadas exercitam as classes reais do Minecraft/Fabric e mixins carregados: bloqueio da entrada não assinada da rede, comando oculto executável, namespace, redirect alternativo, `execute run`, aliases com fonte do jogador e argumentos, sugestões (inclusive após espaço), subcomandos, prefixo fixo dinâmico, subaliases irmãos, exigência vanilla original, árvore efetiva de `CommandTreeS2CPacket` (incluindo ancestral bloqueado como contêiner não executável para um filho permitido), whitelist, backup, reload, JSON inválido, permissões administrativas, console e proteção opcional de chamadas internas. O jogador é simulado e o handler captura pacotes; não há cliente autenticado nesse teste.

O servidor de testes funciona em modo offline/loopback. Houve aviso de consulta às chaves públicas Mojang por DNS indisponível no processo Java; autenticação online não foi validada. Isso não impediu as assertions locais nem o encerramento limpo.

## Pendências obrigatórias antes de v1.0.0

1. **LuckPerms instalado:** carregar a distribuição Fabric compatível com 1.21.1 e testar usuário/grupo/herança/contextos, permissões negativas, bypass e evento de recálculo reenviando o TAB. A consulta à distribuição via `api.modrinth.com` recebeu bloqueio 403 do proxy deste ambiente. Adicione esse domínio na configuração de rede para prosseguir com o download oficial; `cdn.modrinth.com` já consta na política observada.
2. **Sessão de cliente vanilla:** conectar um cliente 1.21.1 sem o mod e verificar TAB, argumentos dinâmicos, original/alias, alteração de cargo durante sessão e entrada de comandos assinados. O hook assinado compila e carrega, mas não foi exercitado com uma sessão autenticada.
3. Depois dos testes, registrar a evidência aqui e atualizar os campos correspondentes em `docs/release-readiness.json`. `scripts/check-release.py` bloqueia o workflow de publicação enquanto os requisitos obrigatórios estiverem falsos; não altere esses campos apenas para fazer o workflow passar.

## Compatibilidade adicional ainda não testada

Cobblemon 1.8.3, SkiesShop, GTS, Kits e outros mods precisam de teste com suas versões reais. Não são dependências obrigatórias nem compatibilidade declarada como comprovada. Verifique especialmente aliases independentes, nós dinâmicos, forks e provedores de sugestões.

## Artefatos e reprodução

- Jar: `build/libs/shirokai-commands-1.0.0+1.21.1.jar`.
- Fontes: `build/libs/shirokai-commands-1.0.0+1.21.1-sources.jar`.
- Relatório JUnit: `build/reports/tests/test/index.html`.
- Resultado da integração: `run/integration/test-result.txt`.
- Log da integração: `run/integration/logs/latest.log`.

Use JDK 21 e execute `./gradlew build` e `python3 scripts/integration-test.py`. O runner remove o resultado anterior e rejeita execução sem assertions. O servidor de teste é encerrado pelo próprio teste; ele não fica como serviço persistente. Veja detalhes do ambiente em [DEVELOPING.md](DEVELOPING.md).

## Regressão de comandos com barras

A correção diferencia a barra do chat das barras nativas do Brigadier. Os testes cobrem `//wand` → `/wand`, JSON com raiz `/wand`, namespace `worldedit:/wand`, distinção entre `wand` e `/wand`, árvore TAB, sugestões com/sem prefixo de chat e REPLACE cujo original tem barra. Também verificam o offset de sugestões de aliases quando a entrada começa por `/`. Os testes dedicados usam raízes Brigadier simuladas de mesmo formato; WorldEdit instalado ainda não foi validado. Regras antigas em que `//wand` virou `wand` precisam ser removidas e cadastradas novamente com a notação `//wand`.

## Filtros de argumentos do TAB

`tabFilters` foi validado com 14 testes unitários de escopo por comando/posição, valores simples e com namespace, aspas, whitespace, limites de palavras e configuração inválida. A integração dedicada usa um provedor de moedas simulado e envia uma requisição real `RequestCommandCompletionsC2SPacket` pelo handler: o pacote de resposta preserva apenas `cash`/`tokens`, mantém o range de substituição e a execução manual das duas formas permanece funcional. Não é uma instalação real do Impactor. A consulta ao código oficial `NickImpact/Impactor`, branch `1.21.1`, em `CurrencyParser.suggestionsFuture` confirmou que o parser gera as duas formas de cada moeda; não foi encontrada opção correspondente em `economy.conf`.

## Jogadores online nas sugestões

A extensão `onlinePlayersOnly` foi validada por 25 novos testes unitários e 16 verificações adicionais dentro do servidor dedicado. Cobrem perfis online/offline, conta fictícia Server, seletores, caixa, próprio jogador, comandos não configurados, índices, subcomandos, namespace, aliases e offsets, composição com `hidePrefixes`, JSON legado, booleano estrito, desconexão e respostas vazias.

Os testes dedicados inserem perfis simulados na lista real do `PlayerManager` durante a verificação e os removem em `finally`. Requisições passam pelo handler real de completions e mixin existente. As respostas preservam ordem/range/tooltip/caixa; um conectado não oferecido pelo provedor não é adicionado. Comandos de pagamento e mensagem simulados continuam executando com destinatários offline digitados manualmente. Nenhuma dependência de Impactor foi adicionada.

`./gradlew test` e `./gradlew build` foram executados com sucesso. Impactor 5.3.5 instalado, seu provedor real de sugestões e clientes conectados ao servidor do usuário ainda não foram executados neste ambiente; a validação é do caminho Brigadier/Fabric com comandos/provedores de teste.

## Ocultar todas as sugestões de uma posição

`hideAll` foi coberto por oito novos testes unitários e quatro verificações dedicadas. A resposta real de completions do comando de teste `/sctafterpay 10 PolicyTest cash ` fica vazia na quarta posição, mesmo com nomes de jogadores online no provedor. Jogadores na segunda posição e moedas curtas na terceira continuam sendo sugeridos. A execução manual de argumento extra permanece a cargo da implementação original. As verificações usam comandos/provedores simulados dentro do Minecraft Fabric 1.21.1, sem declarar teste com o Impactor instalado.

## Aliases com argumentos nomeados

Foram adicionados 27 testes unitários e 21 verificações dedicadas para padrões de entrada/destino com argumentos reorganizados. Incluem aspas/escapes, namespaces e seletores preservados lexicalmente, quantidade exata de argumentos, campos ausentes/repetidos, ciclos/encadeamento, conflitos de IDs e literais já registrados, permissão do destino e bloqueios/HIDE da rota de entrada.

O servidor de integração executa `sctpay cash 123 Alice` na implementação original com os valores reorganizados e a mesma fonte do jogador, conserva a sintaxe original, consulta sugestões de jogadores na posição traduzida e aplica filtros online/moeda ao destino. Foram verificados um comando independente, um parser real de Identifier para `minecraft:stone`, execução dentro de `/execute`, sugestões estáticas quando não há dados suficientes para consultar o provedor original, reload sem duplicação e remoção via `/sc remove` preservando os comandos originais e aliases irmãos.

`./gradlew test` e `./gradlew build` passaram; a suíte dedicada terminou com 96 assertions aprovadas. Esses testes usam comandos/provedores de teste no Fabric/Minecraft real. Impactor, WorldEdit e os demais mods instalados não foram executados na mesma suíte; não se declara compatibilidade comprovada com todos os parsers, forks ou alterações dinâmicas de registro.
