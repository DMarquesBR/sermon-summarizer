# Sermon Summarizer

Backend para transcrever pregações do YouTube e, nas próximas etapas, gerar resumos por canal.

- [Plano de arquitetura e API](docs/architecture.md)
- [Pesquisa de transcrição e limitações do YouTube](docs/research/transcription-options.md)

## Transcrição implementada

O backend Java 25/Quarkus 3.33 recebe uma URL pública de vídeo, baixa a melhor faixa de áudio disponível com `yt-dlp` (ou o vídeo quando necessário), recorta e converte com `ffmpeg`, envia o MP3 à Groq (`whisper-large-v3-turbo`) e devolve texto e segmentos temporizados. Vídeo e áudio são removidos após o job. O limite inicial é de 90 minutos por transcrição e 25 MB para o MP3 enviado à Groq.

Requisitos locais: JDK 25 configurado em `JAVA_HOME`, `yt-dlp`, `ffmpeg` e `ffprobe` no `PATH`. O projeto inclui Maven Wrapper. Em macOS com Homebrew: `brew install yt-dlp ffmpeg`. Configure `GROQ_API_KEY` no ambiente; caminhos dos executáveis, limites e número de workers podem ser alterados em `src/main/resources/application.properties`.

```bash
export GROQ_API_KEY="sua-chave"
./mvnw quarkus:dev
```

Criar um job:

```bash
curl -X POST http://localhost:8080/api/transcription-jobs \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.youtube.com/watch?v=ID_DO_VIDEO","startSeconds":60,"endSeconds":180,"language":"pt"}'
```

A resposta `202` contém `jobId` e `statusUrl`. Consulte `GET /api/transcription-jobs/{jobId}` até `COMPLETED`; o campo `transcript` contém o texto e os segmentos. A documentação OpenAPI fica em `/q/swagger-ui`.

Os jobs ficam apenas na memória nesta primeira implementação: são perdidos ao reiniciar o processo, e os concluídos são removidos após uma hora quando um novo job é submetido. Persistência e autenticação pertencem à próxima etapa do plano. Como não há URL de vídeo da produtora nem chave Groq no repositório, o teste automatizado cobre a extração com uma mídia local e não faz uma chamada real ao YouTube ou à Groq.

## Rodar com Podman

O `Dockerfile` instala Java 25, `yt-dlp` com seus componentes JavaScript, Node 22, `ffmpeg` e `ffprobe`. A construção executa os testes Maven.

```bash
podman build -t sermon-summarizer:local .
podman run --rm --name sermon-summarizer -p 8080:8080 --env-file .env sermon-summarizer:local
```

Defina `GROQ_API_KEY` no arquivo `.env` (uma linha `GROQ_API_KEY=...`) ou passe `-e GROQ_API_KEY` após exportar a variável no shell. A chave não é copiada para a imagem. A API fica em `http://localhost:8080` e a documentação em `http://localhost:8080/q/swagger-ui`.
