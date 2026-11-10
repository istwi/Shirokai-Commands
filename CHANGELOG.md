# Changelog

## 1.0.0

Correção do build de testes: preserva barras nativas de comandos como `//wand`, distingue entrada do chat dos nós Brigadier e corrige offsets de sugestões de aliases com `/`.

Novo filtro opcional `tabFilters` para ocultar valores de argumentos por prefixo e posição, por exemplo `impactor:` no terceiro argumento de `/pay`, sem alterar a execução.

Novo `onlinePlayersOnly` opcional em `tabFilters`: interseção das sugestões originais com jogadores conectados, comparação sem distinguir caixa, metadados preservados e escopo pelo range do argumento. Mantém compatibilidade com filtros por prefixo e não altera a execução.

Novo `hideAll` em `tabFilters` para deixar uma posição sem sugestões, inclusive jogadores online, sem interferir nas posições anteriores ou na execução manual.

Implementação inicial para Fabric 1.21.1 / Java 21:

- Políticas HIDE, BLOCK, REPLACE e ALLOW, whitelist e permissões por regra.
- Aliases com fonte original e sugestões Brigadier.
- Filtragem da árvore enviada ao cliente e bloqueio de comandos recebidos.
- Integração opcional com API LuckPerms 5.4 e atualização por eventos.
- Comandos administrativos, JSON atômico com backup e auditoria assíncrona.
- Testes unitários, integração em servidor dedicado e workflows de build/release.

Ainda não há release publicada. Consulte `docs/VALIDATION.md` antes de criar a tag.
