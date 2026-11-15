import argparse
import json
import os
from pathlib import Path
import subprocess as sp
import tempfile

parser = argparse.ArgumentParser(description='일반판 PLEOS 녹화 원본과 이벤트 시각으로 핵심 데모를 편집합니다.')
parser.add_argument('recordings', type=Path, help='MP4와 같은 이름의 이벤트 JSON이 있는 폴더')
parser.add_argument('output', type=Path, help='완성 MP4 경로')
args = parser.parse_args()
folder = args.recordings.resolve()
output = args.output.resolve()
output.parent.mkdir(parents=True, exist_ok=True)
os.chdir(folder)
chapters=[
    '내비 옆에 자리 잡은 차곡','방향과 속도에 반응하는 차곡','날씨와 화면에 맞춰 달리는 차곡',
    '충전 및 주유를 기억하는 차곡','정비소를 떠날 때 묻는 차곡','하루의 기록을 모아 보는 차곡']
rows={
 'signals':(1,[(0,'PLEOS 전체 화면  ·  내비게이션 옆의 차곡'),
             ('start-drive','D단 출발  ·  계기판 속도에 맞춰 흘러가는 도로'),
             ('mileage','첫 운행에서 묻는 마지막 정비 주행거리')]),
 'racer':(2,[('left','왼쪽 방향지시등을 켜면 차곡 차량도 왼편 차선으로'),
             ('right','오른쪽 방향지시등을 켜면 반대쪽 차선으로'),
             ('boost','32km/h에서 80km/h로 가속할 때 보이는 부스터'),('stop','P단 정차와 함께 멈추는 도로')]),
 'sedan':(3,[(0,'정차 후 넓힌 창에서 현대 세단 스킨 선택'),
             ('wiper','앞유리 와이퍼 작동  ·  차곡이 지속 시간을 관측'),
             ('drive','45km/h로 다시 출발하는 현대 세단'),
             ('turn','왼쪽 신호를 켜면 위 차선으로'),('right','오른쪽 신호를 켜면 아래 차선으로'),
             ('continue','와이퍼를 켜고 계속 달리는 동안의 실제 주행 화면'),
             ('rain','1분 이상 이어진 와이퍼 신호를 반영한 빗길')]),
 'energy':(4,[(0,'정차 중 차곡이 읽은 배터리 잔량 29%'),
              ('rise','배터리 잔량 29% → 83%  ·  정차 중 충전으로 보이는 변화 감지'),
              ('candidate','잔량이 늘면 차가 충전소로 들어가는 연출'),
              ('station','충전소에 머무는 동안 충전 후보를 기록'),('depart','재출발하면 충전소를 나와 도로에 합류'),
              ('question','주행 중 차가 묻는 한마디, “충전비, 얼마였어요?”'),
              ('park-again','정차 후에도 이어서 작성할 수 있는 충전 초안')]),
 'receipt':(4,[(0,'정차 후 차량 일지에서 충전 초안 확인'),
               ('inbox','자동 감지한 충전 기록 이어서 작성'),('edit','충전비 12,000원을 입력하고 확인'),
               ('saved','차량 일지에 남은 충전비 12,000원')]),
 'workshop':(5,[(0,'잠원점 현대자동차 접근  ·  차량 위치를 등록된 정비소와 대조'),
                 ('dwell','정비소 반경에서 P단 정차  ·  위치와 체류 시간을 함께 관측'),
                 ('ready','방문이 감지되면 차가 도로에서 정비소 안으로'),('depart-work','재출발하면 정비소에서 후진해 도로로 복귀'),
                 ('exit','정비소에서 150m 이상 벗어난 뒤 방문 확인'),
                 ('question','“방금 정비하셨나요?”  ·  출차 후 주행 화면에 도착한 질문'),
                 ('items','“무엇을 정비하셨나요?”  ·  정차 후 교체한 부품 선택'),
                 ('repair-save','에어컨 필터 교체 기록과 정차 중 교체 연출')]),
 'diary':(6,[(0,'차량 일지에서 오늘 남긴 충전과 정비 기록 확인'),
              ('detail','정비소 이름과 주행거리를 확인하고 정비비 28,000원 입력'),
              ('price','금액과 함께 보관되는 변경 이력'),('schedule','에어컨 필터 교체 주기를 내 차에 맞게 수정'),
              ('wear','최근 교체일과 교체 후 주행거리로 보는 소모품 사용 현황'),
              ('revised','거리 주기 10,000km → 8,000km  ·  교체 기록을 기준으로 다음 일정 계산'),
              ('stats','충전비 12,000원과 정비비 28,000원  ·  총 40,000원'),
              ('roads','도로별 주행 통계  ·  위치로 추정한 도로 등급별 누적 거리와 시간'),
              ('return','이번 달 차량 일지로 복귀'),
              ('report','기간과 항목을 선택해 정비 리포트 저장'),('learn','정차 중 펼쳐보는 소모품 관리 도감'),
              ('journal-return','차량 일지에 모인 오늘의 지출')]),
 'trips':(6,[(0,'오늘의 드라이브에서 운행별 거리와 관측 시간 확인'),
              ('final','차량 일지  ·  충전과 정비, 두 건의 기록')])
}
def run(args):
    sp.run(['ffmpeg','-hide_banner','-loglevel','error','-y',*args],check=True)

def duration(name):
    seconds = sp.check_output(['ffprobe','-v','error','-select_streams','v:0',
                               '-show_entries','stream=duration','-of','csv=p=0',name+'.mp4'])
    return float(seconds)
def clock(t):
    c=round(max(0,t)*100)
    return f'{c//360000}:{c//6000%60:02}:{c//100%60:02}.{c%100:02}'
header='''[Script Info]
ScriptType: v4.00+
PlayResX: 1920
PlayResY: 1080
ScaledBorderAndShadow: yes
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
'''
for name,size,color,bold in [('Number',44,'B7E6AE',-1),('Title',88,'F6FBF7',-1),('Line',37,'E4EFE7',0),('Clock',110,'EEFAF3',-1),('ClockLabel',34,'AEE3BD',-1),('Place',43,'EFF8F0',-1),('Note',27,'C6D7D1',0)]:
    header+=f'Style: {name},Malgun Gothic,{size},&H00{color},&H00FFFFFF,&H00000000,&H00000000,{bold},0,0,0,100,100,0,0,1,0,0,4,0,0,0,1\n'
header+='[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n'
def event(a,b,sty,x,y,s):
    if b<=a:return ''
    return f'Dialogue: 0,{clock(a)},{clock(b)},{sty},,0,0,0,,{{\\an4\\pos({x},{y})}}{s}\n'
matte='0x122420'
ledger=[]; clips=[]
def title_card(chapter):
    tag = f'title-{chapter:02d}'
    seconds = 2.8
    ass = header + event(0,seconds,'Number',148,416,f'{chapter:02d}  /  {len(chapters):02d}')
    ass += event(0,seconds,'Title',143,549,chapters[chapter-1])
    Path(tag+'.ass').write_text(ass,encoding='utf-8-sig')
    graph = ('drawbox=x=148:y=340:w=1624:h=2:color=0x3C574C:t=fill,'
             f'drawbox=x=148:y=338:w={round(1624*chapter/len(chapters))}:h=6:color=0xABE0B9:t=fill,'
             f'subtitles={tag}.ass,format=yuv420p')
    run(['-f','lavfi','-i',f'color=c={matte}:s=1920x1080:r=30:d={seconds}','-vf',graph,
         '-an','-c:v','libx264','-threads','6','-preset','veryfast','-crf','17',tag+'.mp4'])
    clips.append(tag)
    ledger.append(dict(source='title',start=0,end=seconds,speed=1,framesDuration=duration(tag),chapter=chapter))

def piece(src,markmap,src_start,src_end,framing,idx,rate=1):
    tag=f'part-{src}-{idx}'
    chapter,segments=rows[src]
    a,b=src_start,src_end
    d=(b-a)/rate
    ass=header
    optional={'wear','roads','return'} if src=='diary' else set()
    times=[(markmap.get(t,t),line) for t,line in segments if t not in optional or t in markmap]+[(markmap['end'],'')]
    for (t,line),(n,_) in zip(times,times[1:]):
        ass+=event(max(0,(t-a)/rate),min(d,(n-a)/rate),'Line',130,1005,line)
    graph=f'[0:v]trim=start={a:.5f}:end={b:.5f},setpts=(PTS-STARTPTS)/{rate},fps=30,setsar=1'
    if framing=='duo':
        graph+=f',split=2[total][panel];[total]scale=1224:688,pad=1920:1080:32:137:{matte}[bg];[panel]crop=620:906:1290:68,scale=-2:912[focus];[bg][focus]overlay=1282:8'
    elif framing=='clock':
        graph+=f',scale=1280:720,pad=1920:1080:32:100:{matte}'
        weather = src == 'sedan'
        ass+=event(0,d,'Place',1370,310,'빗길 감지' if weather else '잠원점 현대자동차')
        ass+=event(0,d,'ClockLabel',1374,411,'와이퍼 작동 시간   ×4' if weather else '정차 시간   ×4')
        for i in range(60):ass+=event(i/4,(i+1)/4,'Clock',1366,527,f'00:{i:02d}')
        ass+=event(0,d,'Note',1374,670,'앞유리 와이퍼 연속 작동 중' if weather else '차량 위치와 P단 정차 신호 관측 중')
    elif framing=='full':
        graph+=f',scale=1664:936,pad=1920:1080:128:0:{matte}'
    else:
        graph+=f',crop=1260:904:640:68,scale=-2:936,pad=1920:1080:(ow-iw)/2:0:{matte}'
    graph+=',drawbox=x=0:y=935:w=iw:h=2:color=0x355448:t=fill,drawbox=x=96:y=986:w=4:h=38:color=0xABDEC0:t=fill'
    if framing=='clock' and d>=14:
        graph+=',drawbox=x=1374:y=605:w=450:h=5:color=0x416255:t=fill'
        for j in range(15):graph+=f",drawbox=x={1374+j*30}:y=605:w=30:h=5:color=0xABDEC0:t=fill:enable='gte(t,{j+.01})'"
    if src=='workshop' and idx==2:ass+=event(0,min(2,d),'ClockLabel',1450,890,'01:00  ·  체류 확인')
    if src=='sedan' and idx==2:ass+=event(0,min(2,d),'ClockLabel',1450,890,'01:00  ·  와이퍼 작동')
    graph+=f',subtitles={tag}.ass,format=yuv420p[v]'
    (folder/(tag+'.ass')).write_text(ass,encoding='utf-8-sig')
    source='signals' if src=='racer' else src
    run(['-filter_complex_threads','4','-i',source+'.mp4','-filter_complex',graph,'-map','[v]','-an','-c:v','libx264','-threads','6','-preset','veryfast','-crf','17','-t',str(d),tag+'.mp4'])
    clips.append(tag);ledger.append(dict(source=src,start=a,end=b,speed=rate,framesDuration=duration(tag),chapter=chapter))
    print('rendered',tag,round(d,2),flush=True)
for src in rows:
    source='signals' if src=='racer' else src
    length=duration(source); m={v:t for t,v in json.loads(Path(source+'.json').read_text())}
    # screenrecord starts after codec setup; the signal log includes that start-up time.
    zero=m['end']-length
    m={k:max(0,min(length,t-zero)) for k,t in m.items()}
    if src=='workshop':
        # The first captured dashboard frame with P and 0 km/h follows the console command.
        if Path('dwell-frame.txt').exists():m['dwell']=float(Path('dwell-frame.txt').read_text())
        a=m['dwell']; b=a+60; ends=[(0,a,'full',1),(a,b,'clock',4),(b,m['items'],'full',1),(m['items'],length,'wide',1)]
    elif src=='signals':ends=[(0,m['left'],'full',1)]
    elif src=='racer':ends=[(m['left'],length,'duo',1)]
    elif src=='sedan':
        a=m['wiper']; b=a+60
        assert 0<a<b<length
        ends=[(0,a,'full',1),(a,b,'clock',4),(b,length,'full',1)]
    elif src=='energy':
        if not m['depart'] < m['question'] < m['park-again']:
            raise ValueError('재출발 후 주행 중 비용 질문을 받은 일반판 녹화가 필요합니다.')
        ends=[(0,length,'full',1)]
    else:ends=[(0,length,'wide',1)]
    chapter = rows[src][0]
    if not ledger or ledger[-1]['chapter'] != chapter:
        title_card(chapter)
    for idx,(start,end,framing,rate) in enumerate(ends):
        if not 0 <= start < end <= length:
            raise ValueError(f'{src}: invalid source interval {start}–{end} for {length}s')
        piece(src,m,start,end,framing,idx,rate)
Path('parts.ffconcat').write_text(''.join(f"file '{c}.mp4'\n" for c in clips),'utf8')
metadata=';FFMETADATA1\ntitle=차곡 · 차량일지\n';elapsed=0
for i,title in enumerate(chapters,1):
    end=elapsed+sum(c['framesDuration'] for c in ledger if c['chapter']==i)
    metadata+=f'[CHAPTER]\nTIMEBASE=1/1000\nSTART={round(elapsed*1000)}\nEND={round(end*1000)}\ntitle={title}\n'
    elapsed=end
Path('bookmarks.ffmeta').write_text(metadata,'utf8')
with tempfile.NamedTemporaryFile(dir=output.parent, suffix='.mp4', delete=False) as partial:
    staged = Path(partial.name)
try:
    run(['-f','concat','-safe','0','-i','parts.ffconcat','-i','bookmarks.ffmeta',
         '-map','0:v:0','-map_metadata','1','-map_chapters','1','-c','copy','-movflags','+faststart',str(staged)])
    staged.replace(output)
finally:
    staged.unlink(missing_ok=True)
Path('timeline.json').write_text(json.dumps(ledger,indent=2),'utf8')
print(output,flush=True)
