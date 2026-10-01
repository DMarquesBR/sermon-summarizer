# Plano do backend: resumo de pregações

## Objetivo e escopo

Receber qualquer URL pública de vídeo do YouTube, apresentar metadados para um futuro frontend, obter uma transcrição do vídeo ou de um intervalo, permitir sua edição e gerar textos prontos para copiar no canal escolhido. O backend expõe uma API REST; interface web e envio efetivo para destinatários ficam fora do escopo atual.

## Decisão que condiciona o produto

O fluxo definido é: **URL pública → download temporário → extração e recorte de áudio → Groq Whisper → transcrição**. Vídeos públicos ainda podem falhar por indisponibilidade, restrição geográfica/etária, transmissão ao vivo ou mudanças no YouTube. A API deve informar a falha e permitir colar uma transcrição.

**Provedor de transcrição escolhido: Groq**, modelo `whisper-large-v3-turbo`, com preço publicado de US$ 0,04/hora de áudio (mínimo faturável de 10 segundos por chamada). Sua API `/openai/v1/audio/transcriptions` recebe o arquivo de áudio produzido pelo backend. Para o download, o candidato técnico é `yt-dlp`; para extração, recorte e compressão do áudio, `ffmpeg`. O backend deve receber só URLs canônicas de vídeo do YouTube e passar argumentos separados aos processos, nunca montar comandos de shell com texto do usuário. Pesquisa de provedores e fontes: [opções de transcrição](research/transcription-options.md).

**Conformidade antes de publicar:** a [política para clientes da API do YouTube](https://developers.google.com/youtube/terms/developer-policies) restringe baixar, importar ou armazenar cópias de conteúdo audiovisual sem aprovação prévia por escrito do YouTube e também restringe separar componentes de áudio/vídeo. Como o produto usará a API para metadados, este fluxo deve ser avaliado e, se necessário, autorizado antes do lançamento público. A implementação técnica não resolve essa condição contratual. A disponibilidade pública de um vídeo tampouco comprova direitos para processá-lo.

## Jornada e contratos propostos

1. `POST /api/videos/resolve` recebe `{ "url": "..." }`. Normaliza URLs `youtube.com/watch`, `youtu.be` e `youtube.com/shorts`; extrai apenas o ID de vídeo; consulta YouTube Data API `videos.list` com `part=snippet,contentDetails` (título, canal, duração e miniatura). Retorna `videoId`, `canonicalUrl`, `title`, `channelTitle`, `durationSeconds`, `thumbnailUrl`, `available` e eventual motivo de indisponibilidade. A URL fornecida nunca vira destino de requisição HTTP arbitrária.
2. `POST /api/transcription-jobs` recebe `videoId`, `startSeconds?`, `endSeconds?`. Valida `0 <= início < fim <= duração`, tamanho máximo de trecho. O áudio é sempre em português brasileiro; enviar `language=pt` explicitamente à Groq. Retorna `202`, `jobId` e `statusUrl`. O worker baixa o vídeo para diretório temporário exclusivo do job, extrai apenas o áudio com `ffmpeg`, recorta o intervalo solicitado antes do envio, converte para formato/tamanho aceito e envia o arquivo à Groq. Pedir `response_format=verbose_json` e marcas de tempo por segmento. Ajustar os tempos da resposta pelo início do intervalo e persistir segmentos, texto e proveniência.
3. `GET /api/transcription-jobs/{id}` informa `QUEUED | DOWNLOADING | EXTRACTING | TRANSCRIBING | COMPLETED | FAILED`, resultado ou erro recuperável (`DOWNLOAD_UNAVAILABLE`, `AUDIO_EXTRACTION_FAILED`, `PROVIDER_LIMIT`, `VIDEO_UNAVAILABLE`, etc.). O frontend pode consultar periodicamente. O endpoint devolve a transcrição concluída; arquivos de vídeo/áudio são apagados após processamento, inclusive em falhas.
4. `POST /api/transcripts` permite cadastrar texto fornecido pelo usuário como alternativa quando o provedor não atender. `GET /api/transcripts/{id}` devolve texto, segmentos quando houver, origem e revisão. `PUT /api/transcripts/{id}` recebe o texto editado e `expectedRevision`; salva nova revisão e rejeita conflito com `409`.
5. `GET/POST/PUT/DELETE /api/templates` administra templates por canal, com nome, instruções, idioma, tom, público, tamanho alvo, formato e variáveis permitidas. Templates padrão são somente leitura; templates do usuário são versionados. Validar configuração e oferecer `POST /api/templates/{id}/preview-prompt` sem consumir LLM pode ajudar no futuro, mas não é necessário no MVP.
6. `POST /api/generation-jobs` recebe `transcriptId`, `transcriptRevision`, `channel`, `templateId`, `templateRevision` e parâmetros permitidos (por exemplo `maxCharacters`). Retorna `202` e `jobId`. `GET /api/generation-jobs/{id}` devolve estado, texto gerado e metadados do modelo. O usuário pode editar o resultado depois; `PUT /api/generated-messages/{id}` guarda revisão final, se o produto precisar de histórico.

Erros usam JSON estável com `code`, `message`, `retryable` e `details`. Requisições de criação aceitam chave de idempotência para evitar cobrança duplicada em repetição do clique.

## Canais e templates

| Canal | Saída inicial sugerida | Preferências úteis |
| --- | --- | --- |
| WhatsApp | Texto curto, parágrafos pequenos, possível chamada para ação | Caracteres alvo, emojis permitidos, incluir URL |
| E-mail | Assunto, prévia e corpo | Tom, tamanho, saudação, chamada para ação |
| Telegram | Mensagem com parágrafos e link | Comprimento, Markdown simples |
| Boletim/site | Título, introdução, pontos centrais e conclusão | Tamanho, subtítulos, referências |
| Rede social | Legenda curta | Caracteres alvo, hashtags opcionais |

Começar com WhatsApp e e-mail; os demais podem ser presets de template quando o formato estiver estabilizado. O backend devolve o conteúdo pronto para copiar e não envia mensagens.

Campos controlados pelo servidor no prompt: papel, tarefa de resumir, formato do canal, limite de tamanho e regras de fidelidade. A transcrição e trechos editados são **dados não confiáveis**, delimitados e enviados como entrada; instruções dentro da fala não alteram as regras do sistema. Pedir ao modelo que não invente citações bíblicas ou afirmações ausentes. Se referências bíblicas forem exibidas, marcar como citadas na transcrição ou solicitar revisão humana. O usuário revisa o resultado antes de compartilhar.

## Arquitetura sugerida

```text
API REST
  ├─ Vídeo → YouTube metadata
  ├─ Transcrição → job persistido → yt-dlp → ffmpeg → Groq Whisper
  ├─ Edição e templates → SQLite
  └─ Geração → job persistido → OpenAI Responses API
```

- **Java 25 + Quarkus 3.33.x LTS**, em JVM inicialmente. Quarkus REST + Jackson, REST Client, Bean Validation, Scheduler, OpenAPI e Health. Selecionar o patch mais recente da linha 3.33 ao implementar.
- **SQLite** é útil já no MVP para jobs, versões e templates, inclusive após reinício. Usar extensão JDBC do Quarkiverse, migrações Flyway se compatíveis, WAL e transações curtas. Uma instância e volume persistente; se houver múltiplas réplicas ou muitos jobs, migrar para PostgreSQL e um worker/fila próprios.
- Definir interfaces pequenas para `VideoMetadataProvider`, `MediaDownloader`, `AudioPreprocessor`, `TranscriptProvider` e `TextGenerator`. O orquestrador do job controla a passagem de um arquivo temporário entre download, extração e Groq. Texto manual entra por outro caso de uso.
- A Groq aceita até 25 MB no plano gratuito e 100 MB no plano de desenvolvimento, conforme a documentação consultada. Arquivos maiores exigem divisão em partes com sobreposição, transcrição de cada parte e ajuste dos tempos. O custo total depende da duração processada; limitar tamanho, duração e número de tentativas.
- Preferir baixar somente a faixa de áudio com `yt-dlp -f ba/b` quando disponível; isso evita transferir vídeo desnecessariamente. Se o formato exigir, baixar o contêiner de vídeo e extrair o áudio. Usar `ffprobe` para confirmar duração e faixa de áudio. Limitar bytes em disco, duração do processo e concorrência; limpar temporários em `finally` e periodicamente após reinício.
- Worker com limite de concorrência, timeout e tentativas exponenciais apenas para erros transitórios. Guardar estados no banco e recuperar jobs órfãos após reinício. Registrar ID de correlação, duração e consumo, nunca chave de API nem transcrição completa em logs.
- Segredos por variáveis de ambiente; cotas por usuário/IP, limite de duração e de caracteres, validação de URL, orçamento de tokens, autenticação antes de expor histórico e templates de múltiplos usuários. Definir retenção e exclusão de transcrições e resumos.

## Geração com OpenAI

Usar a **Responses API** com modelo configurável e saída estruturada por canal (por exemplo `{subject, preview, body}` no e-mail; `{body}` no WhatsApp). Validar limites e campos após a resposta. Guardar IDs de template e revisão, ID de modelo, data, parâmetros e consumo retornado; não depender do modelo para impor sozinho o limite exato de caracteres. Para transcrições que excedam a janela ou orçamento, dividir por segmentos e consolidar resumos intermediários, mantendo a ligação com a fonte e evitando repetir trechos.

## Sequência de entrega

**Estado em 01/10/2026:** a primeira fatia do backend já implementa `POST/GET /api/transcription-jobs`, download temporário com `yt-dlp`, extração/recorte com `ffmpeg` e transcrição pela Groq. Os jobs ficam em memória nesta etapa. Persistência em SQLite, metadados/miniatura, edição de texto, templates e geração de resumos continuam planejados.

1. **Prova técnica e de conformidade:** testar `yt-dlp` + `ffmpeg` + Groq com vídeos públicos em português, com/sem legendas, curtos/longos e recortes. Medir falhas, tempo, tamanho, qualidade e custo. Verificar os termos e a autorização necessária antes de oferecer download de vídeos de terceiros em produção.
2. **MVP de API:** resolver URL, baixar e extrair áudio, transcrever com Groq, aceitar transcrição manual, permitir edição, oferecer dois templates padrão e templates personalizados, gerar para WhatsApp/e-mail, persistir jobs e publicar OpenAPI.
3. **Operação:** autenticação e isolamento por usuário, políticas de retenção, métricas/custos, cotas e monitoramento.

## Decisões em aberto

1. Haverá contas de usuário desde o MVP, ou uma instalação privada para um único operador? Isto muda autenticação e modelo de dados.
2. Qual limite de duração/volume diário e qual orçamento mensal aceitável para OpenAI e Groq?
3. Qual será a conclusão da avaliação dos termos do YouTube para o lançamento público deste fluxo de download e extração?

## Fontes técnicas

- [Quarkus 3.33 LTS e suporte a Java 25](https://quarkus.io/blog/quarkus-3-33-released/)
- [Quarkus REST Client](https://quarkus.io/guides/rest-client/)
- [Quarkus e SQLite](https://quarkus.io/guides/datasource/)
- [OpenAI Responses API para texto](https://developers.openai.com/api/docs/guides/text)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
- [YouTube captions.download](https://developers.google.com/youtube/v3/docs/captions/download)
- [Políticas da API do YouTube](https://developers.google.com/youtube/terms/developer-policies)
- [Groq Speech to Text](https://console.groq.com/docs/speech-to-text)
- [Groq Whisper Large v3 Turbo](https://console.groq.com/docs/model/whisper-large-v3-turbo)
- [yt-dlp, opções de download e extração de áudio](https://github.com/yt-dlp/yt-dlp/blob/master/README.md)
