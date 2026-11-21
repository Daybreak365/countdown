from pathlib import Path
import random, math

out=Path(__file__).resolve().parents[1]/'app/assets'
random.seed(27)
parts=[]
def add(s): parts.append(s)
def rect(x,y,w,h,c,opacity=1): add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{c}" opacity="{opacity}"/>')
def poly(points,c,opacity=1): add(f'<polygon points="{" ".join(f"{x},{y}" for x,y in points)}" fill="{c}" opacity="{opacity}"/>')
def cube(x,y,s,top='#dbe8ed',left='#849da7',right='#647d8c',h=None):
 h=s if h is None else h
 poly([(x,y),(x+s,y-s/2),(x+2*s,y),(x+s,y+s/2)],top)
 poly([(x,y),(x+s,y+s/2),(x+s,y+s/2+h),(x,y+h)],left)
 poly([(x+s,y+s/2),(x+2*s,y),(x+2*s,y+h),(x+s,y+s/2+h)],right)
def pine(x,y,s=1,lit=False):
 rect(x-8*s,y-45*s,16*s,54*s,'#4f3930')
 cols=['#173f40','#20514b','#2a6052']
 for i,(w,yy) in enumerate([(140,0),(112,-60),(84,-113),(54,-164),(28,-207)]):
  w*=s;yy=y+yy*s
  pts=[(x-w/2,yy),(x-w/2,yy-23*s),(x-w/2+18*s,yy-23*s),(x-w/2+18*s,yy-44*s),(x-8*s,yy-44*s),(x-8*s,yy-64*s),(x+8*s,yy-64*s),(x+8*s,yy-44*s),(x+w/2-18*s,yy-44*s),(x+w/2-18*s,yy-23*s),(x+w/2,yy-23*s),(x+w/2,yy)]
  poly(pts,cols[i%3]);poly([(x,yy-64*s),(x+8*s,yy-64*s),(x+8*s,yy-44*s),(x+w/2-18*s,yy-44*s),(x+w/2-18*s,yy-23*s),(x+w/2,yy-23*s),(x+w/2,yy),(x,yy)],'#123c37',.55)
  rect(x-w/2,yy-23*s,18*s,6*s,'#b8d4d2');rect(x-w/2+18*s,yy-44*s,max(8,w/2-26*s),5*s,'#d5e8e1')
  rect(x+8*s,yy-44*s,max(8,w/2-26*s),5*s,'#b0ccc7');rect(x+w/2-18*s,yy-23*s,18*s,6*s,'#d5e8e1')
  if lit:
   for j in range(5-i if i<4 else 1):
    ox=x-w*.35+j*w*.7/max(1,4-i);oy=yy-15*s+(j%2)*-11*s
    col=['#efba64','#f17668','#bdddba'][j%3]
    add(f'<circle cx="{ox}" cy="{oy}" r="{12*s}" fill="{col}" opacity=".10"/>')
    rect(ox-3*s,oy-3*s,6*s,6*s,col)
 if lit:
  sy=y-284*s
  rect(x-3*s,sy-12*s,6*s,30*s,'#fce49b');rect(x-12*s,sy-3*s,24*s,8*s,'#fce49b');rect(x-7*s,sy-7*s,14*s,16*s,'#e9b96a')
  add(f'<circle cx="{x}" cy="{sy}" r="{42*s}" fill="url(#starGlow)"/>')
def house(x,y,w=150,h=86):
 poly([(x,y),(x+w*.68,y+34),(x+w*.68,y+34-h),(x,y-h)],'#694438')
 poly([(x+w*.68,y+34),(x+w,y+12),(x+w,y+12-h),(x+w*.68,y+34-h)],'#3b3430')
 for yy in range(int(y-h)+8,int(y),12):
  add(f'<path d="M{x},{yy} L{x+w*.68},{yy+34}" stroke="#9e6650" stroke-width="2" opacity=".4"/>')
 poly([(x-12,y-h),(x+w*.33,y-h-60),(x+w*.78,y-h-28),(x+w*.68+12,y-h+34)],'#ecf1ec')
 poly([(x+w*.33,y-h-60),(x+w+12,y-h-48),(x+w+12,y-h+12),(x+w*.78,y-h-28)],'#abc3c6')
 poly([(x-12,y-h+8),(x+w*.68+12,y-h+42),(x+w*.68+12,y-h+34),(x-12,y-h)],'#ac6b52')
 rect(x+20,y-h+30,25,32,'#e0a768');rect(x+22,y-h+32,21,28,'#ffe2a2');rect(x+30,y-h+30,4,32,'#6d493a');rect(x+20,y-h+45,25,4,'#6d493a')
 rect(x+57,y-h+47,23,41,'#392d28');rect(x+62,y-h+52,14,24,'#cd995d');rect(x+68,y-h+52,2,24,'#41302b')
 cube(x+w*.68+12,y-h-75,12,'#e8eeec','#805442','#584135',44)
 for i in range(4): rect(x+w*.68+20+i*6,y-h-89-i*18,16+i*4,8,'#afcccc',.10-i*.017)
 for i in range(7): rect(x+4+i*14,y-h+17+i*4.5,4,5,['#e7b96c','#e58872','#a3cba2'][i%3])
def gift(x,y,s,c):
 cube(x,y,s,c,{'#b34e4c':'#773c3e','#dcbb70':'#a48449','#749d85':'#426b5c'}.get(c,'#604952'), '#303d37',s)
 poly([(x+s*.8,y-s*.4),(x+s*1.03,y-s*.52),(x+s*1.23,y+s*.38),(x+s,y+s*.5)],'#f4d18a')
 rect(x+s*.9,y+s*.5,s*.22,s,'#f4d18a')

add('<svg xmlns="http://www.w3.org/2000/svg" width="1440" height="630" viewBox="0 0 1440 630"><defs><linearGradient id="sky" x2="0" y2="1"><stop stop-color="#0c1827"/><stop offset=".60" stop-color="#213e45"/><stop offset="1" stop-color="#607d7d"/></linearGradient><linearGradient id="aurora" x2="0" y2="1"><stop stop-color="#8ab99a" stop-opacity="0"/><stop offset=".5" stop-color="#8ac5ad" stop-opacity=".19"/><stop offset="1" stop-color="#78aeab" stop-opacity="0"/></linearGradient><radialGradient id="starGlow"><stop stop-color="#efc876" stop-opacity=".30"/><stop offset="1" stop-color="#efc876" stop-opacity="0"/></radialGradient><linearGradient id="fade"><stop stop-color="#0c1821" stop-opacity=".65"/><stop offset=".55" stop-color="#0c1821" stop-opacity=".1"/><stop offset="1" stop-color="#0c1821" stop-opacity="0"/></linearGradient></defs>')
rect(0,0,1440,630,'url(#sky)')
add('<path d="M360,35 Q680,235 950,105 T1500,160 L1500,245 Q1270,100 970,200 T430,170Z" fill="url(#aurora)"/>')
add('<path d="M510,8 Q830,160 1060,48 T1500,20 L1500,190 Q1320,74 1070,127 T530,93Z" fill="url(#aurora)"/>')
for i in range(105):
 x=random.randint(55,1420);y=random.randint(20,330);s=random.choice([1,1,2,2,3]);rect(x,y,s,s,'#e0e8d7',random.uniform(.18,.8))
for i in range(3):
 rect(1248-i*7,72+i*7,48+i*14,31-i*2,'#dfe6ce',.92)
rect(1293,72,15,10,'#a0b2b1',.5);rect(1250,85,11,12,'#8da6aa',.4)
poly([(400,430),(400,355),(459,355),(459,314),(505,314),(505,269),(550,269),(550,310),(612,310),(612,350),(692,350),(692,382),(747,382),(747,410),(837,410),(837,315),(880,315),(880,281),(927,281),(927,228),(962,228),(962,281),(1020,281),(1020,313),(1070,313),(1070,358),(1123,358),(1123,285),(1170,285),(1170,252),(1221,252),(1221,293),(1270,293),(1270,322),(1310,322),(1310,353),(1370,353),(1370,390),(1440,390),(1440,500)],'#45636b',.52)
poly([(900,310),(927,310),(927,265),(962,265),(962,281),(980,281),(980,309),(1019,309),(1019,331),(927,331)],'#94b6b4',.17)
poly([(0,494),(80,478),(180,480),(270,452),(460,483),(620,423),(730,444),(840,424),(1040,436),(1180,401),(1440,459),(1440,630),(0,630)],'#abc3c6')
poly([(0,525),(170,486),(280,520),(407,487),(528,517),(652,481),(752,496),(926,456),(1076,486),(1267,446),(1440,482),(1440,630),(0,630)],'#d4e2e3')
for x,y,s in [(578,451,.41),(640,430,.50),(687,462,.38),(1217,419,.42),(1369,435,.60),(1330,403,.39),(830,447,.40)]: pine(x,y,s)
house(720,513,145,79);house(1184,503,140,84)
house(1309,546,118,69)
poly([(922,486),(1059,460),(1190,496),(1095,559),(935,536)],'#eaf0e7')
poly([(967,535),(1100,535),(1190,554),(1206,610),(1088,630),(914,613),(930,571)],'#c5d8d7')
pine(1050,488,1.13,True)
gift(932,528,17,'#b34e4c');gift(974,541,14,'#dcbb70');gift(1111,523,19,'#b34e4c');gift(1133,548,13,'#749d85');gift(928,561,12,'#749d85')
for x,y in [(671,541),(881,506),(1220,553)]:
 rect(x,y-70,5,75,'#544846');cube(x-8,y-90,11,'#e4dabb','#817054','#685f4f',17)
 rect(x-4,y-85,12,13,'#f5d697');add(f'<circle cx="{x+2}" cy="{y-80}" r="38" fill="url(#starGlow)"/>')
for i in range(65):
 x=random.randint(60,1420);y=random.randint(426,630);rect(x,y,random.randint(2,12),2,'#99b5bf',.3)
# Foreground snow blocks and two tiny players facing the tree.
for x,y,s in [(410,603,37),(454,611,26),(620,607,23),(1271,602,32),(1350,614,38)]: cube(x,y,s,'#e5eceb','#9eb9c1','#698b9c',s*.6)
for x,y,c in [(1020,561,'#a86450'),(1060,571,'#60879a')]:
 rect(x,y-23,12,21,c);rect(x-2,y-35,16,13,'#be956f');rect(x-2,y-35,16,5,'#463830');rect(x+1,y-2,4,12,'#2d414e');rect(x+8,y-2,4,12,'#2d414e')
for i in range(40):
 x=random.randint(0,1440);y=random.randint(5,625);s=random.choice([2,3,4]);rect(x,y,s,s,'#ecf1ed',random.uniform(.15,.65))
rect(0,0,1440,630,'url(#fade)')
add('</svg>')
(out/'winter-scene.svg').write_text(''.join(parts))

# Original chunky numerals. Extruded pixel lettering replaces a copyrighted logo.
digits={'2':['11111','00001','00001','11111','10000','10000','11111'], '0':['11111','10001','10001','10001','10001','10001','11111'], '7':['11111','00001','00010','00100','00100','01000','01000']}
parts=[];add('<svg xmlns="http://www.w3.org/2000/svg" width="480" height="174" viewBox="0 0 480 174">')
for layer,off,col in [(0,8,'#233b30'),(1,4,'#aebeb0'),(2,0,'#e9f0dd')]:
 for j,c in enumerate('2027'):
  for y,row in enumerate(digits[c]):
   for x,b in enumerate(row):
    if b=='1':rect(j*116+x*20+off,y*20+off,20,20,col)
add('</svg>');(out/'year.svg').write_text(''.join(parts))

parts=[];add('<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256" viewBox="0 0 256 256"><rect width="256" height="256" rx="52" fill="#17231f"/>')
poly([(128,33),(220,83),(128,133),(36,83)],'#bed6be');poly([(36,83),(128,133),(128,227),(36,176)],'#398461');poly([(128,133),(220,83),(220,176),(128,227)],'#226146')
rect(120,55,16,50,'#c79750');rect(98,77,60,16,'#c79750');rect(120,55,16,38,'#f5d999');rect(99,76,51,16,'#f5d999')
rect(80,133,14,17,'#d76655');rect(155,148,14,17,'#f0c378');rect(180,130,12,15,'#d76655')
add('</svg>');(out/'launcher-icon.svg').write_text(''.join(parts))
print('Vector art created')
