import time
import os
import whisper

MODELS_DIR = './models'
audio = 'test_audio.mp3'

print('[small] 加载模型...')
t0 = time.time()
model = whisper.load_model('small', download_root=MODELS_DIR)
print(f'  加载耗时: {time.time()-t0:.1f}秒')

print('[small] 开始识别（约2.6分钟音频）...')
t1 = time.time()
result = model.transcribe(audio, language='zh', verbose=False)
t2 = time.time()
elapsed = t2 - t1
print(f'  识别耗时: {elapsed:.1f}秒')
print(f'  生成字数: {len(result["text"])}')
print(f'  实时比: {elapsed/158.8:.2f}x （1秒音频需要{elapsed/158.8:.2f}秒来识别）')
