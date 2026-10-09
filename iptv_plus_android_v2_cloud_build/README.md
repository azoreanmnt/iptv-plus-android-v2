# IPTV+ Android — versão 0.2.0

Projeto Android nativo Kotlin + Jetpack Compose para Android TV/Google TV e smartphones Android. A interface usa NavigationRail em ecrãs de TV/largos e navegação inferior em telemóveis.

## Incluído
- Ícone vetorial próprio IPTV+ e nome da aplicação.
- Leitor integrado Media3/ExoPlayer com suporte a HTTP/HTTPS e HLS/M3U8.
- Importação remota M3U/M3U8 (parser básico de `#EXTINF` e URLs).
- Login Xtream Codes API (servidor, utilizador e palavra-passe), consulta de canais em direto e construção de URLs de stream.
- Carregamento XMLTV/EPG simples, pesquisa de canais e favoritos em memória.
- Oito cores de destaque.
- Workflow GitHub Actions que compila e publica `app-debug.apk` como artefacto.

## Limitações conhecidas
- Esta é uma base funcional inicial, não uma aplicação IPTV de produção certificada. Testa com o teu servidor/lista autorizados.
- Xtream Codes implementa autenticação e canais em direto; categorias, VOD, séries, catch-up e dados completos do EPG ainda precisam de integração e testes específicos com cada servidor.
- Parser M3U/XMLTV é intencionalmente simples; listas muito grandes podem consumir memória. O EPG é mostrado como lista de programas, não totalmente associado a cada canal.
- Favoritos, cores e credenciais não são persistidos após fechar a aplicação. As credenciais não são guardadas em ficheiro.
- HTTP não encripta tráfego nem credenciais. Preferir HTTPS sempre que disponível.
- O ícone é vectorial e pode ser ajustado visualmente antes de uma publicação na loja.

## Compilar online (sem Android Studio)
1. Cria um repositório GitHub privado.
2. Extrai o ZIP e carrega **o conteúdo da pasta** para a raiz do repositório, mantendo `.github/workflows/android-apk.yml`.
3. Abre o separador **Actions** e escolhe **Build IPTV+ APK**.
4. Clica **Run workflow** e aguarda o visto verde.
5. Abre a execução e descarrega o artefacto `IPTVPlus-debug-apk` na secção **Artifacts**. Extrai o ZIP para obter `app-debug.apk`.

## Instalar
Transfere `app-debug.apk` para o dispositivo Android e instala-o. Poderá ser necessário permitir a instalação dessa origem. Para Android TV, usa um método de transferência de ficheiros compatível com a TV.

## Importante
Usa apenas listas, credenciais e streams para os quais tens autorização. Este projeto não inclui canais nem credenciais de demonstração reais.
