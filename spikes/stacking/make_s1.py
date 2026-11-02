"""Spike S1: build the stacking test. Writes new files only: four colored squares into content/s1test/ and two
templates, s1cpu.xml (CPU) and s1gpu.xml (NVIDIA through EVA), in transcoder/templates/. Usage: python make_s1.py <WSE home>"""
import os
import sys
from PIL import Image, ImageDraw

wse = sys.argv[1]
content = os.path.join(wse, 'content', 's1test')
os.makedirs(content, exist_ok=False)

# slot, color, decode-side position (source pixels, 1280x720 test pattern). Each square overlaps the next one, so
# whichever color shows in an overlap is the one drawn later (on top).
DECODE = [(1, 'red', 100, 100), (8, 'green', 250, 150), (16, 'blue', 400, 200), (17, 'yellow', 550, 250)]
# encode-side squares on the 720p output only: a low slot (2) placed over the decode squares tests whether encode
# overlays draw over decode overlays regardless of slot number; slot 18 tests order among encode overlays.
ENCODE = [(2, 'magenta', 600, 330), (18, 'cyan', 700, 380)]

for slot, color, _, _ in DECODE + ENCODE:
	img = Image.new('RGBA', (220, 220), color)
	d = ImageDraw.Draw(img)
	d.rectangle([0, 0, 219, 219], outline='black', width=6)
	d.text((12, 12), 'slot %d' % slot, fill='black')
	img.save(os.path.join(content, 'slot%d.png' % slot))

def overlay(slot, x, y):
	return '''						<Overlay>
							<Enable>true</Enable>
							<Name>s1slot%d</Name>
							<Index>%d</Index>
							<ImagePath>${com.wowza.wms.context.VHostConfigHome}/content/s1test/slot%d.png</ImagePath>
							<CheckForUpdates>false</CheckForUpdates>
							<Opacity>100</Opacity>
							<Location>
								<X>%d</X>
								<Y>%d</Y>
								<Width>${ImageWidth}</Width>
								<Height>${ImageHeight}</Height>
								<Align>left,top</Align>
							</Location>
						</Overlay>
''' % (slot, slot, slot, x, y)

def template(desc, dec, scale, enc):
	dec_ovl = ''.join(overlay(s, x, y) for s, _, x, y in DECODE)
	enc_ovl = ''.join(overlay(s, x, y) for s, _, x, y in ENCODE)
	return '''<Root>
	<Transcode>
		<Description>Spike S1 stacking test (%s). Safe to delete.</Description>
		<Decode>
			<Video>
				<Implementation>%s</Implementation>
				<Deinterlace>false</Deinterlace>
				<EnableDynamicOverlays>true</EnableDynamicOverlays>
				<Overlays>
%s				</Overlays>
			</Video>
		</Decode>
		<Scale>
			<Implementation>%s</Implementation>
		</Scale>
		<Encodes>
			<Encode>
				<Name>720p</Name>
				<Enable>true</Enable>
				<StreamName>${SourceStreamName}_720p</StreamName>
				<Video>
					<Codec>H.264</Codec>
					<Implementation>%s</Implementation>
					<GPUID>-1</GPUID>
					<Profile>main</Profile>
					<Bitrate>2500000</Bitrate>
					<KeyFrameInterval>
						<FollowSource>false</FollowSource>
						<Interval>60</Interval>
					</KeyFrameInterval>
					<FrameSize>
						<FitMode>fit-height</FitMode>
						<Width>0</Width>
						<Height>720</Height>
						<Crop>0,0,0,0</Crop>
						<SourceRectangle></SourceRectangle>
					</FrameSize>
					<Overlays>
%s					</Overlays>
				</Video>
				<Audio>
					<Codec>PassThru</Codec>
				</Audio>
			</Encode>
		</Encodes>
	</Transcode>
</Root>
''' % (desc, dec, dec_ovl, scale, enc, enc_ovl)

tdir = os.path.join(wse, 'transcoder', 'templates')
for name, desc, dec, scale, enc in [('s1cpu', 'CPU', 'default', 'default', 'default'),
		('s1gpu', 'NVIDIA through EVA', 'NVCUVID_EVA', 'CUDA_EVA', 'NVENC_EVA')]:
	path = os.path.join(tdir, name + '.xml')
	if os.path.exists(path):
		sys.exit('exists, not overwriting: ' + path)
	with open(path, 'w', newline='\n') as f:
		f.write(template(desc, dec, scale, enc))
	print('wrote', path)
print('wrote', content)
