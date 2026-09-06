"""Exercise the production Windows renderer with mutable project data, clips and motion."""
import gzip,json,struct,subprocess,sys,tempfile,zlib,zipfile
from pathlib import Path
from PIL import Image,ImageChops

def main():
    exe=str(Path(sys.argv[1]).resolve())
    with tempfile.TemporaryDirectory() as directory:
        d=Path(directory)
        Image.new('RGB',(158,263),(30,220,60)).save(d/'art.png')
        resources={
            'body':{'type':'project-card','layout':'illustrated-full','width':158,'height':360,'titleHeight':41,'descriptionHeight':56,'titleTextSize':20,'descriptionTextSize':11},
            'label':{'type':'project-badge-text','width':158,'height':140,'headerCenterY':37,'valueCenterY':69,'unitCenterY':98,'headerSize':18,'valueSize':44,'unitSize':20}}
        scene={'api':3,'id':'illustrated-runtime-test','name':'Illustrated runtime test','canvas':{'width':640,'height':360,'fps':30},'timeline':{'frames':2,'clock':'absolute','implicitAnimation':False},'features':['project-card-data'],'background':'#111111','resources':resources,'objects':[
            {'id':'card@0','kind':'card','cardIndex':0,'frame':0,'lifespan':{'start':0,'end':1},'resource':'body','properties':{'position.x':{'dense':{'start':0,'values':[100,140]},'timeline':'absolute','extrapolate':'hold'},'artwork.reveal':200}},
            {'id':'badgeText@0','kind':'badgeText','cardIndex':0,'frame':0,'lifespan':{'start':0,'end':1},'resource':'label','properties':{'position.x':300}}], 'selectors':[]}
        payload=gzip.compress(json.dumps(scene).encode());(d/'test.renderer3').write_bytes(b'CCRNDR03'+struct.pack('>III',1,len(payload),zlib.crc32(payload))+payload)
        card={'title':'Candy Heaven','description':'All words must remain visible when a description wraps across several lines.','value':'12 Years','badge_header':'Age','image':'art.png'}
        def render(name,card):
            (d/'project.json').write_text(json.dumps({'version':6,'name':'Test','cards':[card]}))
            subprocess.run([exe,'--render-frames',str(d/'test.renderer3'),str(d/'project.json'),str(d/name),'0,1'],check=True)
            return [Image.open(d/name/f'frame-{f:05}.png').convert('RGB') for f in [0,1]]
        first=render('first',card)
        assert first[0].getpixel((110,150))==(30,220,60),'artwork missing or wrong position'
        assert first[0].getpixel((90,150))==(17,17,17),'position applied twice/ignored'
        assert first[1].getpixel((150,150))==(30,220,60),'dense motion ignored'
        assert first[1].getpixel((110,150))==(17,17,17),'card failed to move'
        assert first[0].getpixel((110,230))!=(30,220,60),'artwork reveal ignored'
        changed=render('changed',{**card,'title':'New title','value':'99 Years'})
        assert ImageChops.difference(first[0].crop((100,263,258,304)),changed[0].crop((100,263,258,304))).getbbox(),'title not bound to project'
        assert ImageChops.difference(first[0].crop((300,0,458,140)),changed[0].crop((300,0,458,140))).getbbox(),'badge not bound to project'
        print('Production renderer: artwork, position, dense motion, reveal, title and badge binding PASS')
if __name__=='__main__':main()
