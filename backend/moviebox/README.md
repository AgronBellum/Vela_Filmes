# Backend da fonte Servidor Vela

Serviço local usado pelo app Vela para consultar e resolver opções do MovieBox. A lógica de
assinatura, token de visitante, idiomas, streams e legendas foi adaptada do projeto AnyFilm.

## CasaOS

1. Copie esta pasta para o servidor.
2. Crie um app personalizado no CasaOS usando `docker-compose.yml`.
3. Troque `VELA_TOKEN_SECRET` por uma chave aleatória longa.
4. Publique a porta TCP 8787 apenas na rede local.

Teste: `http://192.168.0.238:8787/health`.

O app consulta `POST /v1/options` ao abrir a lista de fontes e chama
`GET /v1/resolve/{token}` somente quando o usuário escolhe uma opção. Assim os links de vídeo
não ficam antigos antes da reprodução.
