# Shirokai Commands

Mod **server-side** para Minecraft **1.21.1**, Fabric e Java **21**. Gerencia visibilidade, execução, aliases, whitelist e auditoria de comandos por jogador. Não precisa ser instalado nos clientes.

**Estado:** implementação em validação; ainda não há release publicada. Resultados e pendências reais em [docs/VALIDATION.md](docs/VALIDATION.md). Compatibilidade com Cobblemon 1.8.3, SkiesShop, GTS e Kits ainda não foi comprovada. Nenhum deles é dependência obrigatória.

## Requisitos e instalação

Versões usadas na compilação e nos testes: Fabric Loader **0.16.14**, Fabric API **0.116.6+1.21.1**, Yarn **1.21.1+build.3**, Loom **1.8.13**, Gradle **8.10.2**, JDK **21**. A integração opcional usa a API oficial LuckPerms **5.4**, sem empacotá-la no jar.

1. Prepare um servidor dedicado Fabric 1.21.1 com Java 21 e Fabric API.
2. Coloque `shirokai-commands-1.0.0+1.21.1.jar` em `mods/` do servidor.
3. Opcionalmente instale LuckPerms para Fabric compatível com essa versão.
4. Inicie o servidor. O arquivo `config/shirokai-commands.json` será criado automaticamente com política permissiva e nenhuma regra.
5. Configure regras pelo JSON ou pelos comandos administrativos. Teste com um jogador sem OP e sem bypass.

O exemplo em [config/shirokai-commands.example.json](config/shirokai-commands.example.json) contém comandos de outros mods: só ative substituições quando seus comandos originais estiverem registrados. O arquivo não é copiado automaticamente para a configuração ativa.

## Aliases com argumentos nomeados e ordem personalizada

Use a propriedade opcional `argumentAliases` na raiz do JSON. Ela é genérica: qualquer comando de destino registrado no Brigadier pode ser usado, sem dependência de Impactor. O mapa padrão é vazio. Exemplo para adicionar a sintaxe `/pay <moeda> <quantidade> <jogador>` sem remover a original:

```json
"argumentAliases": {
  "pay_cash": {
    "alias": "pay cash {quantidade} {jogador}",
    "target": "pay {quantidade} {jogador} cash",
    "extendExisting": true
  },
  "pay_dollars": {
    "alias": "pay dollars {quantidade} {jogador}",
    "target": "pay {quantidade} {jogador} dollars",
    "extendExisting": true
  },
  "pay_tokens": {
    "alias": "pay tokens {quantidade} {jogador}",
    "target": "pay {quantidade} {jogador} tokens",
    "extendExisting": true
  }
}
```

Assim `/pay cash 100 Alice` encaminha `pay 100 Alice cash` ao comando original, com a fonte do jogador. As moedas precisam existir no mod de economia; o Shirokai não as cria nem libera transferências proibidas. O `/pay 100 Alice cash` original continua funcionando.

- `alias` contém um prefixo de literais e depois argumentos `{nome}`. Cada nome deve aparecer uma única vez.
- `target` começa com uma raiz fixa de comando original e pode reorganizar os mesmos `{nomes}`, cada um exatamente uma vez, com literais fixos entre eles.
- `extendExisting: true` permite adicionar um novo subcomando literal a uma raiz existente. Não substitui a raiz nem seus argumentos originais. Como literais têm precedência sobre argumentos dinâmicos no Brigadier, escolha um prefixo que não represente um valor importante da sintaxe antiga. Um caminho já registrado, como `pay cash` se outro mod já o possuir, é rejeitado. Raízes com redirecionamento ou extensões sem um novo literal não são aceitas; use uma raiz nova nesses casos.
- Os IDs, como `pay_cash`, são únicos e usam letras minúsculas, números, `_` ou `-`. A permissão adicional padrão é `shirokaicommands.alias.pay_cash`, permitida quando indefinida. `permission` pode configurar outro node. Bypass autorizado ignora essa permissão adicional, mas não as exigências originais do destino.

Outra opção, com uma raiz nova e moeda como argumento:

```json
"argumentAliases": {
  "pagar": {
    "alias": "pagar {moeda} {quantidade} {jogador}",
    "target": "pay {quantidade} {jogador} {moeda}",
    "suggestions": {
      "moeda": ["cash", "dollars", "tokens"]
    }
  }
}
```

Esse segundo exemplo cria `/pagar cash 100 Alice`. `suggestions` é opcional: configure valores fixos quando o argumento vier antes dos parâmetros que o provedor original precisa. Não são inventados valores de quantidade ou de jogador para consultar o destino. Quando os parâmetros anteriores necessários já estão disponíveis, as sugestões são consultadas no provedor original e mapeadas de volta ao argumento do alias. Nomes de jogadores não são adicionados automaticamente.

Os `tabFilters` continuam configurados conforme a ordem **do destino**. Por exemplo, `pay` com `argumentIndex: 2` e `onlinePlayersOnly: true` filtra o jogador mesmo quando ele aparece na terceira posição do novo `/pay cash 100 Alice`. O filtro de moeda na posição 3 também acompanha a moeda movida para o início. Ranges e valores são projetados apenas para avaliar a política; os placeholders vazios dessa projeção nunca são executados.

Valores com espaços precisam de aspas. A forma lexical dos argumentos, inclusive aspas, escapes e identificadores como `minecraft:stone`, é encaminhada ao destino; o parser original decide como interpretá-los. O número de argumentos deve ser exato. Não há suporte a literais depois do primeiro placeholder em `alias`, variáveis dentro de tokens ou repetição/descarte de argumentos no destino. Para texto greedy, considere que aspas mantidas podem fazer parte do texto, conforme o parser do comando original.

A execução usa um snapshot da árvore de comandos original, com seus requisitos, redirecionamentos e callbacks, sem privilégios de console. Não encadeia templates entre si. Referências estáticas a outro template, ciclos, prefixos ambíguos, campos inválidos e conflitos com aliases antigos são rejeitados antes de salvar. BLOCK/HIDE e permissões da rota digitada e do destino continuam sendo avaliados; um template não ignora um BLOCK ou REPLACE aplicado ao destino. `/sc list` inclui os templates e `/sc remove pay_cash` remove um pelo ID. `/sc replace` continua criando apenas os aliases de prefixo antigos; padrões `{nome}` são configurados no JSON.

Depois de instalar o jar atualizado, reinicie o servidor. Para alterar apenas o JSON, use `/sc reload`: alterações inválidas mantêm a configuração válida anterior. Use reload também após mudanças dinâmicas na árvore de comandos de outros mods. Estruturas especiais, alterações tardias de registro, forks personalizados e argumentos de chat assinados precisam de validação com o conjunto real de mods; o Shirokai não reassina mensagens. A compatibilidade instalada com Impactor 5.3.5 ainda não foi comprovada pelos testes locais.

## Modos e prioridade

| Modo | TAB | Execução manual |
| --- | --- | --- |
| `hide` | Oculto | Permitida, respeitando as permissões originais |
| `block` | Oculto | Bloqueada antes da implementação original |
| `replace` | Original oculto, alias permitido visível | Original bloqueado; alias usa a mesma fonte e permissões |
| `allow` | Visível quando o comando original é acessível | Permitida pela política; permissões originais continuam valendo |

Ordem: proteção de `/sc` e `/shirokaicommands` contra regras; console; bypass autorizado; `block` com `security: true`; regra mais específica de subcomando; substituição; ocultação; política padrão. Entre regras sobrepostas, o caminho mais específico vence, exceto bloqueios `security`. Um ancestral bloqueado com um filho explicitamente permitido pode aparecer no TAB apenas como contêiner, sem marcador de execução; o comando do ancestral continua bloqueado. Não é permitido criar regras para os comandos administrativos protegidos; a permissão administrativa continua obrigatória.

Uma permissão positiva **não anula BLOCK** nem concede o comando original de REPLACE. Para isso existe o bypass. Permissões individuais de comandos restringem ALLOW/REPLACE e permissões de alias restringem o alias. HIDE não é convertido em bloqueio por permissão da política; somente a permissão original do comando controla sua execução.

## JSON

```json
{
  "configVersion": 1,
  "settings": {
    "opBypass": true,
    "defaultMode": "allow",
    "whitelistEnabled": false,
    "autoRefreshTab": true,
    "luckPermsIntegration": true,
    "logBlockedCommands": true,
    "silentBlockedCommands": false,
    "guardInternalPlayerCommands": false
  },
  "messages": {
    "blocked": "§cVocê não tem permissão para usar este comando.",
    "unknown": "§cComando desconhecido.",
    "reload": "§aConfigurações recarregadas com sucesso.",
    "aliasConflict": "§cEste alias já está sendo utilizado."
  },
  "commands": {
    "calculate": {"mode": "hide"},
    "center": {"mode": "block", "message": "§cUse os recursos do servidor."},
    "balance": {"mode": "replace", "alias": "money"},
    "warp spawn": {"mode": "replace", "alias": "spawn"},
    "kit": {"mode": "allow"},
    "kit admin": {"mode": "block", "security": true}
  }
}
```

`defaultMode` aceita `allow` ou `block`. Whitelist habilitada bloqueia os comandos sem regra, independentemente do modo padrão. ALLOW libera explicitamente um caminho e seus descendentes; uma regra mais específica pode restringi-los. HIDE continua executável mesmo em whitelist. Em blacklist, mantenha `defaultMode: "allow"` e cadastre os bloqueios desejados.

Cada regra aceita `permission` para substituir o node gerado automaticamente, `message` para a recusa e `security` em BLOCK para impedir exceções em subcaminhos. `security` também respeita o bypass autorizado, conforme a prioridade definida. Caminhos não incluem `/`; o nome raiz é canônico em minúsculas. Argumentos preservam a caixa. IDs de permissão substituem espaços e pontuação por pontos: `kit admin` → `kit.admin`; colisões desses IDs são rejeitadas.

A configuração é validada antes de ser aplicada. Alterações administrativas usam gravação atômica no mesmo diretório e backup `.bak`, fora da thread principal. Reload inválido mantém a configuração anterior em memória. Se a configuração inicial for inválida, entra uma política de bloqueio padrão; console e administração autorizada permanecem disponíveis para corrigir e recarregar. O JSON inválido não é sobrescrito automaticamente.

## Administração

`/shirokaicommands` e `/sc` oferecem:

```text
/sc help
/sc hide "calculate"
/sc block "kit admin"
/sc replace "warp spawn" "spawn"
/sc replace "kit claim inicial" "kitinicial"
/sc allow "kit claim"
/sc remove "kit.admin"
/sc whitelist on
/sc whitelist off
/sc list
/sc inspect "kit admin"
/sc test Jogador "warp spawn"
/sc reload
```

Use aspas em caminhos com espaços. `remove` aceita o caminho exato ou seu ID de permissão. Regras duplicadas são rejeitadas; remova uma regra antes de cadastrar outra para o mesmo caminho. `inspect` avalia com a fonte de quem consulta; `test` usa a fonte do jogador online indicado. São simulações: não executam o comando e informam separadamente a política e a possibilidade de parse do original. Uma consulta feita pelo console informa seu bypass, portanto use `test` para avaliar jogadores.

Alterações só são confirmadas depois da gravação; outra alteração concorrente é recusada enquanto houver uma gravação pendente. O TAB é reenviado após alteração/reload. A opção `autoRefreshTab` controla os eventos automáticos de permissões e mudança de mundo; reload administrativo sempre atualiza.

## Ocultar moedas com namespace nas sugestões

As opções `impactor:cash` e `impactor:tokens` dentro de `/pay` são **argumentos**, não comandos. Para manter somente as formas curtas nas sugestões de `/pay <valor> <jogador> <moeda>`, adicione na raiz de `config/shirokai-commands.json` (fora de `settings` e `commands`):

```json
"tabFilters": [
  {
    "command": "pay",
    "argumentIndex": 3,
    "hidePrefixes": ["impactor:"]
  }
]
```

Execute `/sc reload` e reabra o chat. O filtro remove as sugestões que começam com `impactor:` na terceira posição de argumento; preserva `cash`, `tokens` e outras formas curtas já fornecidas pelo Impactor. Ele **não renomeia moedas**, não altera saldos/permissões e não bloqueia digitar a forma completa manualmente. Não remova os IDs `impactor:...` da configuração de moedas do Impactor.

`argumentIndex` começa em 1 após o caminho `command`. Para outro comando ou estrutura, configure outra entrada com a posição correspondente; um filtro de `pay` não afeta `give`, outros comandos nem nomes de jogadores na segunda posição. Também aceita caminhos como `economy pay`. As sugestões de aliases são avaliadas com seu comando original expandido. O filtro é cosmético e vale inclusive para administradores com bypass. A lista padrão é vazia, mantendo as sugestões atuais até você ativá-lo.

Essa função trata respostas de sugestões enviadas pelo servidor, portanto não exige módulo cliente. Sugestões criadas inteiramente por um mod local continuam fora do controle do servidor.

## Sugerir somente jogadores online

O filtro opcional `onlinePlayersOnly` mantém somente sugestões que correspondam, sem distinguir maiúsculas/minúsculas, ao nome real (`GameProfile`) de um jogador conectado no momento de processar a resposta. Ele preserva o texto original, a ordem, os ranges e os tooltips. O jogador que digitou o comando não é excluído se o próprio provedor o sugerir.

Exemplo para `/pay <valor> <jogador> <moeda>`, preservando também o filtro de moedas:

```json
"tabFilters": [
  {
    "command": "pay",
    "argumentIndex": 2,
    "onlinePlayersOnly": true,
    "hidePrefixes": ["@"]
  },
  {
    "command": "pay",
    "argumentIndex": 3,
    "hidePrefixes": ["impactor:"]
  },
  {
    "command": "tpa",
    "argumentIndex": 1,
    "onlinePlayersOnly": true
  },
  {
    "command": "msg",
    "argumentIndex": 1,
    "onlinePlayersOnly": true
  }
]
```

Adicione a lista na raiz de `config/shirokai-commands.json`; se ela já existir, acrescente as entradas sem duplicar a chave `tabFilters`. A lista acima é ilustrativa: nenhum desses filtros é ativado por padrão. `hidePrefixes` é opcional com `onlinePlayersOnly: true`; também é permitido usar apenas prefixos, como antes. Uma entrada sem nenhuma condição ativa é rejeitada. `onlinePlayersOnly` deve ser um booleano JSON (`true`/`false`), com padrão `false`.

`argumentIndex` conta a partir de 1 após o caminho configurado. Para `/economy pay <valor> <jogador>`, use `"command": "economy pay"` e `"argumentIndex": 2`. O filtro respeita limites de tokens, diferencia `pay` de `payroll`, trata namespaces de raiz pela política normal e traduz os offsets dos aliases para o original. Apenas o argumento abrangido pelo range da sugestão é filtrado; um destinatário offline digitado manualmente não interfere nas sugestões de moeda ou mensagem que vêm depois.

Seletores (`@a`, `@r`, `@s`, `@p`, `@e`), contas fictícias como `Server` e nomes offline são removidos por não corresponderem aos nomes reais conectados. O filtro **nunca acrescenta jogadores**: se o provedor original não oferecer um nome por permissão, visibilidade ou vanish, o Shirokai também não o oferece. Uma conta real conectada chamada `Server` é um nome real e é tratada como qualquer outro jogador. Nicks decorativos e nomes de exibição que não correspondam ao GameProfile são excluídos.

A lista do `PlayerManager` é lida uma vez por resposta, na thread do servidor, depois da conclusão do provedor original. Cada resposta usa um snapshot atual, sem cache entre pedidos; desconexões passam a valer no próximo processamento. Nenhuma varredura por tick nem modificação de registros de outros mods é introduzida.

Essa opção **filtra sugestões, não bloqueia execução**. Se alguém digitar manualmente um offline, `Server` ou um seletor, a decisão continua com o comando original e suas permissões; saldos e destinatários não são alterados pelo Shirokai. Ela funciona com argumentos de comandos Minecraft/Fabric/outros mods que passem pelas sugestões Brigadier do servidor, sem dependência de Impactor. Sugestões calculadas apenas no cliente, respostas que evitem esse handler e listas já armazenadas no cliente não são controladas por esse filtro. Nenhuma compatibilidade específica com Impactor 5.3.5 é declarada como comprovada sem teste com o mod instalado.

Após trocar o jar, reinicie o servidor. Para alterações posteriores somente no JSON, use `/sc reload`. Configuração inválida mantém a última versão válida em memória.

## Não mostrar nenhuma sugestão após a moeda

Para `/pay <valor> <jogador> <moeda>` terminar as sugestões na moeda, use `hideAll: true` no quarto argumento. Mantenha os filtros das posições anteriores:

```json
"tabFilters": [
  {"command": "pay", "argumentIndex": 2, "onlinePlayersOnly": true},
  {"command": "pay", "argumentIndex": 3, "hidePrefixes": ["impactor:"]},
  {"command": "pay", "argumentIndex": 4, "hideAll": true}
]
```

`hideAll` é opcional e tem padrão `false`. Quando ativado, remove **todas** as sugestões que editam a posição configurada, inclusive nomes online, `Server` e seletores. Não precisa de `hidePrefixes` nem `onlinePlayersOnly`; é uma condição ativa por si só. Não modifica argumentos anteriores, outros comandos ou a execução manual. Não bloqueia argumentos extras: essa decisão permanece no comando original. A opção vale para respostas Brigadier do servidor, não para sugestões criadas inteiramente no cliente.

Depois de instalar o jar que suporta a opção, reinicie o servidor. Alterações posteriores do JSON podem usar `/sc reload`.

## Comandos com duas barras (WorldEdit)

Use a notação completa do chat nos comandos administrativos:

```text
/sc block "//wand"
/sc block "//set"
/sc hide "//paste"
```

O JSON armazena os nomes internos com **uma barra a menos**: `"/wand": {"mode": "block"}` corresponde a `//wand` no chat. A barra inicial do chat é removida exatamente uma vez; a barra que pertence ao comando é preservada. Assim `/wand` e `//wand` são comandos distintos.

Se uma build anterior criou a regra `wand` ao receber `//wand`, remova essa regra (`/sc remove "wand"`) e cadastre novamente com `/sc block "//wand"`. Não é possível migrar automaticamente essa intenção sem confundir um comando normal com o WorldEdit.

OP e `shirokaicommands.bypass` continuam ignorando as restrições por projeto. Para testar o TAB sem bypass, use uma conta comum ou configure `opBypass: false` e remova o bypass efetivo no LuckPerms. Após substituir o jar, reinicie o servidor; `/sc reload` recarrega apenas o JSON.

## LuckPerms e fallback

| Permissão | Finalidade | Padrão quando indefinida |
| --- | --- | --- |
| `shirokaicommands.admin` | Administração completa | OP nível 4 / console |
| `shirokaicommands.reload` | Recarregar sem administração completa | OP nível 4 / console |
| `shirokaicommands.bypass` | Ver e executar originais restritos | Não concedido; `opBypass` pode liberar OP nível 4 |
| `shirokaicommands.command.<id>` | Restringir ALLOW/REPLACE | Permitido |
| `shirokaicommands.alias.<id-original>` | Restringir o alias | Permitido |

O mod consulta `CachedPermissionData` com as `QueryOptions` contextuais do próprio LuckPerms. Grupos e herança ficam sob responsabilidade do LuckPerms. FALSE explícito prevalece sobre o padrão. Durante indisponibilidade dos dados de um usuário carregado pelo LuckPerms, a checagem falha de forma restritiva. `opBypass: false` remove o bypass automático de OP; então conceda bypass apenas pelo node apropriado.

Exemplos:

```text
/lp group admin permission set shirokaicommands.admin true
/lp group admin permission set shirokaicommands.bypass true
/lp group moderador permission set shirokaicommands.reload true
/lp group default permission set shirokaicommands.command.kit.claim false
/lp user Jogador permission set shirokaicommands.alias.warp.spawn false
/lp group vip permission set shirokaicommands.alias.balance true server=shirokai
```

O último exemplo depende do contexto `server` configurado no seu LuckPerms. Não são feitas consultas remotas nem carregamentos bloqueantes durante comandos. Eventos `UserDataRecalculateEvent` solicitam nova árvore TAB na thread do servidor; mudança de mundo também solicita atualização. Permissões são reavaliadas na execução, sem cache próprio persistente.

Sem LuckPerms, ou com integração desativada, administração usa nível vanilla 4 e console; `opBypass` controla OP, e os nodes por regra/alias usam o padrão permitido. As exigências originais do Brigadier continuam obrigatórias. Conceder um node Shirokai nunca eleva a permissão vanilla de um comando.

## Aliases, subcomandos e segurança

`balance → money`, `warp spawn → spawn`, `kit claim inicial → kitinicial` e `gts → mercado` preservam a fonte do jogador. Argumentos adicionais são anexados ao original; valores fixos do prefixo são reparsados pelo Brigadier. Sugestões do original são remapeadas para o texto do alias.

Aliases devem usar literais minúsculos `[a-z0-9_-]`, separados por espaços. Não podem conflitar com raízes de comandos existentes, com caminhos originais gerenciados, nem com outro alias sobreposto. Ciclos são rejeitados por essa mesma validação. Subaliases irmãos, como `economia saldo` e `economia pagar`, podem compartilhar uma nova raiz. Alterações removem os nós anteriores das três tabelas internas do Brigadier antes do registro; a árvore original dos outros mods permanece intacta.

A filtragem ocorre na construção de `CommandTreeS2CPacket`: nós ocultos são removidos antes do envio. Sugestões pedidas explicitamente ao servidor também são filtradas. As entradas de execução de comandos assinados e não assinados são verificadas; cadeias de redirecionamento como `execute ... run` são examinadas. Namespaces são normalizados para a política e redirecionamentos que apontam aos nós conhecidos também são tratados. Implementações que reutilizam o mesmo objeto executor são associadas quando identificáveis.

**Limitações que exigem teste no seu conjunto de mods:**

- Wrappers implementados por outro mod com lógica própria, nomes diferentes e executores independentes não podem ser inferidos automaticamente. Há aviso no log; cadastre regras para essas rotas e use `/sc test`.
- Chamadas internas diretas a `CommandDispatcher.execute` não passam pela entrada de rede. `guardInternalPlayerCommands: true` estende a proteção às chamadas de `CommandManager.execute` com fonte de jogador, podendo afetar outros mods; console não é bloqueado. Chamadas diretas que evitam ambos continuam fora desse escopo.
- Regras com valores de argumentos dinâmicos são avaliadas no texto concreto. Não é possível remover do pacote um único valor de um nó de argumento genérico; a filtragem da resposta de sugestões e o bloqueio de execução cuidam desses valores. Caminhos com argumentos entre aspas precisam corresponder ao texto real do comando.
- Árvores/forks próprios, parsers especiais e provedores de sugestões de outros mods precisam de validação. O alias usa um argumento greedy para encaminhamento, portanto a árvore de argumentos original não é copiada integralmente ao cliente. Argumentos de chat assinados não são reassinados nem promovidos a mensagens seguras pelo alias.
- A descoberta estrutural de regras ausentes/dinâmicas gera aviso. O original de uma substituição deve ter sua raiz registrada. O mod não cria comandos ausentes de dependências opcionais.
- Cobblemon, SkiesShop, GTS, Kits e LuckPerms instalado em servidor real ainda exigem a matriz de validação documentada. Não há declaração de compatibilidade comprovada com esses mods.

## Auditoria

`logs/shirokai-commands/audit.log` registra horário UTC, UUID/nome, raiz do comando, regra e resultado BLOCK, além de operações administrativas. Todos os argumentos são ocultados; não se registra apenas uma lista de palavras como `password`, pois qualquer argumento pode conter informação sensível. Há limite de frequência por jogador e fila assíncrona limitada. Em saturação, registros podem ser descartados para proteger a thread principal; não é um sistema de auditoria forense sem perdas. Configure rotação externa do arquivo em produção. `logBlockedCommands: false` desativa a auditoria de bloqueios e alterações; `silentBlockedCommands` silencia recusas.

## Compilar, testar e baixar

```sh
./gradlew build
python3 scripts/integration-test.py
```

Use um **JDK** 21 completo. O wrapper verifica o SHA-256 da distribuição Gradle. O build gera `build/libs/shirokai-commands-1.0.0+1.21.1.jar` e um jar de fontes. Os testes unitários ficam em `build/reports/tests/test/index.html`. O runner de integração cria apenas arquivos ignorados em `run/integration`, usa um servidor offline restrito a loopback e exige um resultado novo `PASS`; um processo com exit code zero sem assertions não é aceito.

No GitHub: abra **Actions → Build and tests → execução concluída → Artifacts** e baixe o artifact do mod. O artifact só é enviado depois de build e integração bem-sucedidos. Execuções comuns não criam releases.

O workflow de release responde apenas a tags versionadas, verifica a correspondência com `build.gradle` e o changelog, executa os dois conjuntos de testes e anexa jar, fontes e SHA-256. O preflight bloqueia a publicação enquanto `docs/release-readiness.json` tiver verificações obrigatórias pendentes. Antes de criar `v1.0.0`, conclua a matriz pendente em [docs/VALIDATION.md](docs/VALIDATION.md). Nenhuma tag ou release foi criada nesta implementação.

Para atualizar: pare o servidor, faça backup do JSON e do jar anterior, substitua o jar e reinicie. Confira os avisos de regras/aliases e teste com um jogador sem bypass. Mudanças apenas no JSON podem usar `/sc reload`.

Detalhes de arquitetura e desenvolvimento: [docs/DEVELOPING.md](docs/DEVELOPING.md).
