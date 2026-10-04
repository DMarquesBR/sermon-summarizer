# Sermon Summarizer

Backend para transcrever pregações do YouTube e gerar mensagens curtas para WhatsApp.

- [Plano de arquitetura e API](docs/architecture.md)
- [Pesquisa de transcrição e limitações do YouTube](docs/research/transcription-options.md)

## Transcrição implementada

O backend Java 25/Quarkus 3.33 recebe uma URL pública de vídeo, baixa a melhor faixa de áudio disponível com `yt-dlp` (ou o vídeo quando necessário), recorta e converte com `ffmpeg`, envia o MP3 à Groq (`whisper-large-v3-turbo`) e devolve texto e segmentos temporizados. Os áudios são sempre em português brasileiro; todas as chamadas de transcrição enviam `language=pt` explicitamente. Vídeo e áudio são removidos após o job. O limite inicial é de 90 minutos por transcrição e 25 MB para o MP3 enviado à Groq.

Requisitos locais: JDK 25 configurado em `JAVA_HOME`, `yt-dlp`, `ffmpeg` e `ffprobe` no `PATH`. O projeto inclui Maven Wrapper. Em macOS com Homebrew: `brew install yt-dlp ffmpeg`. Configure `GROQ_API_KEY` no ambiente; caminhos dos executáveis, limites e número de workers podem ser alterados em `src/main/resources/application.properties`.

```bash
export GROQ_API_KEY="sua-chave"
./mvnw quarkus:dev
```

Criar um job:

```bash
curl -X POST http://localhost:8080/api/transcription-jobs \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=ID_DO_VIDEO","startSeconds":60,"endSeconds":180}'
```

A resposta `202` contém `jobId` e `statusUrl`. Consulte `GET /api/transcription-jobs/{jobId}` até `COMPLETED`; o campo `transcript` contém o texto e os segmentos. A documentação OpenAPI fica em `/q/swagger-ui`.

O campo `video` contém o título e a descrição obtidos pelo `yt-dlp` no mesmo download, sem exigir chave da API do YouTube. Exemplo parcial de resposta concluída:

```json
{
  "videoId": "dQw4w9WgXcQ",
  "status": "COMPLETED",
  "video": {
    "title": "Título do vídeo",
    "description": "Descrição do vídeo\nSegunda linha"
  },
  "transcript": {
    "text": "Texto transcrito...",
    "segments": []
  }
}
```

`video` fica `null` até os metadados estarem disponíveis e também quando o JSON não puder ser lido ou não contiver título válido; isso não impede a transcrição. Descrição ausente retorna `""`. Se a transcrição falhar após obter os metadados, eles continuam na resposta. O JSON de metadados é removido junto com a mídia temporária, inclusive nas falhas.

## Gerar resumo para WhatsApp

Envie a transcrição inteira ou editada para gerar uma mensagem. Título, descrição e `customPrompt` são opcionais. O prompt personalizado define a estrutura quando informado; as regras de fidelidade ao orador e o limite de 2.000 caracteres continuam valendo. Sem prompt personalizado, o resumo inclui título, orador identificável, introdução, tópicos principais, chamada à ação e oração curta.

```bash
curl -X POST http://localhost:8080/api/generation-jobs -H 'Content-Type: application/json' -d '{"transcriptText":"Texto completo da pregação...","video":{"title":"Uma vida de oração","description":"Série sobre oração"},"customPrompt":null}'
```

O `POST` responde `202` com `jobId` e `statusUrl`. Consulte `GET /api/generation-jobs/{jobId}` até o estado `COMPLETED`; `result.text` contém a mensagem pronta para copiar e `result.characterCount` informa seu tamanho. Se houver prompt personalizado, passe-o em `customPrompt`; texto ausente ou em branco seleciona o padrão. A API recebe JSON e aceita até 200.000 caracteres de transcrição, prompt e metadados combinados.

Configure `DEEPSEEK_API_KEY` no `.env` local ou nas variáveis de ambiente. Quarkus lê `.env` no modo de desenvolvimento. Os jobs de transcrição e geração ficam em memória, são perdidos ao reiniciar o processo e jobs concluídos são removidos após uma hora quando um novo job é submetido. A geração usa `deepseek-flash` por padrão; modelo e endpoint podem ser alterados em `src/main/resources/application.properties`. Os testes usam servidor HTTP local e chave fictícia; não fazem chamadas pagas ao provedor.

Como não há URL de vídeo da produtora nem chave Groq no repositório, o teste automatizado cobre a extração com uma mídia local e não faz uma chamada real ao YouTube ou à Groq.

## Rodar com Podman

O `Dockerfile` instala Java 25, `yt-dlp` com seus componentes JavaScript, Node 22, `ffmpeg` e `ffprobe`. A construção executa os testes Maven.

```bash
podman build -t sermon-summarizer:local .
podman run --rm --name sermon-summarizer -p 8080:8080 --env-file .env sermon-summarizer:local
```

Defina `GROQ_API_KEY` e `DEEPSEEK_API_KEY` no arquivo `.env` (uma chave por linha) ou passe `-e GROQ_API_KEY -e DEEPSEEK_API_KEY` após exportar as variáveis no shell. As chaves não são copiadas para a imagem. A API fica em `http://localhost:8080` e a documentação em `http://localhost:8080/q/swagger-ui`.
