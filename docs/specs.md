Crie um app de câmera Android em Kotlin, SEM interpretador Python. 
Ao tirar a foto, ela é processada automaticamente por um port em Kotlin do script `corrigir_foto.py` e salva na galeria já corrigida.

## Script de referência
- Caminho: "C:\Users\Fabricio\Projects\Aurum\tools\corrigir_foto.py"
- Ele corrige fotos com visual de iPhone: balanço de branco, níveis, CLAHE, HDR local, vibrance, tom dourado, realce de textura e grão de pele.
- O resultado do preset `--iphone` já foi validado e é o padrão do app. O port deve reproduzir o resultado do script, não reinventar os algoritmos.

## Port
- Usar o OpenCV Android SDK (as mesmas funções do script: cvtColor LAB/HSV/YCrCb, CLAHE, bilateralFilter, GaussianBlur, resize).
- Cada etapa do pipeline vira uma classe implementando uma interface `ImageProcessingStep` (WhiteBalanceStep, LevelsStep, DynamicRangeStep, LocalHdrStep, VibranceStep, GoldenToneStep, SkinTextureStep).
- Um `PhotoEnhancer` recebe a lista de etapas via injeção de dependência (Hilt). Parâmetros em `data class EnhanceParams`, com o preset iPhone como padrão (mesmos valores do script com `--iphone`).
- Manter a mesma ordem do script: textura e grão sempre por último.
- Cuidado com as diferenças: OpenCV usa BGR, o Bitmap do Android usa RGBA; a escala de LAB/HSV em 8 bits do OpenCV deve ser a mesma do script.

## Validação do port (obrigatória)
- Gerar com o script Python imagens de referência a partir de 1 foto de teste: "C:\Users\Fabricio\Projects\Aurum\app\src\main\assets\foto_teste.jpg"
- Criar um teste instrumentado que processa as mesmas fotos em Kotlin e compara com as referências (PSNR > 40 dB), com o grão desativado para a comparação ser determinística.
- Só seguir para a câmera depois que o port passar nesse teste.

## App
- Kotlin, Jetpack Compose, MVVM, Hilt.
- CameraX para preview e captura em resolução máxima, câmeras frontal e traseira, flash e toque para focar.
- Fluxo: tirar foto → processar fora da main thread (com indicador discreto, sem travar a câmera) → salvar na galeria (MediaStore, pasta própria do app) com os metadados EXIF originais e a orientação correta.
- Selfie salva sem espelhamento.
- Sem tela de antes/depois e sem sliders.
- Limitar o APK a arm64-v8a.
- Uso pessoal; não precisa de publicação na Play Store.

## Fase 2 (não fazer agora)
- Preview aproximado em tempo real com shader OpenGL via `CameraEffect` do CameraX (balanço de branco, curva, vibrance e tom dourado).

## Primeiros passos
1. Confirme as versões de OpenCV Android SDK, CameraX e Kotlin e me diga antes de começar.
2. Faça o port e a validação contra o Python antes de construir a câmera.
3. Depois do primeiro build, informe o tamanho do APK e o tempo de processamento por foto.