import math
C=54.0
def ring(r0, amp, lobes, amp2=0.0, lobes2=0, phase=0.0, n=240, closed=True):
    pts=[]
    for i in range(n):
        t=2*math.pi*i/n
        r=r0+amp*math.sin(lobes*t+phase)+amp2*math.sin(lobes2*t+1.3)
        pts.append((C+r*math.cos(t), C+r*math.sin(t)))
    d="M%.2f,%.2f"%pts[0]+"".join("L%.2f,%.2f"%p for p in pts[1:])+"Z"
    return d
# A sound wave, not a sine: mix of lobes so it reads as audio, not a gear.
outer=ring(24.0, 2.3, 9, 0.6, 23, 0.4)
inner=ring(14.5, 1.7, 6, 0.4, 17, 1.1)
core="M54,54 m-6,0 a6,6 0 1,0 12,0 a6,6 0 1,0 -12,0"
bg_svg='''<radialGradient id="bg" cx="54" cy="46" r="70" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1C0B38"/><stop offset="1" stop-color="#06040C"/></radialGradient>'''
layers=[ # (path, stroke, width, opacity, fill): wider, fainter strokes stand in for bloom
 (outer,'url(#go)',13.0,0.05,None),(outer,'url(#go)',10.0,0.07,None),(outer,'url(#go)',7.5,0.10,None),(outer,'url(#go)',5.4,0.16,None),(outer,'url(#go)',3.4,1.0,None),
 (inner,'url(#gi)',9.5,0.06,None),(inner,'url(#gi)',7.0,0.09,None),(inner,'url(#gi)',5.0,0.13,None),(inner,'url(#gi)',3.6,0.2,None),(inner,'url(#gi)',2.4,1.0,None),
 (core,None,0,1.0,'url(#gc)')]
defs='''<radialGradient id="halo" cx="54" cy="54" r="34" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#7B3CFF" stop-opacity=".35"/><stop offset=".55" stop-color="#7B3CFF" stop-opacity=".12"/><stop offset="1" stop-color="#7B3CFF" stop-opacity="0"/></radialGradient>
<linearGradient id="go" x1="30" y1="28" x2="78" y2="80" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FF2E8C"/><stop offset=".55" stop-color="#B05CFF"/><stop offset="1" stop-color="#2EC8FF"/></linearGradient>
<linearGradient id="gi" x1="40" y1="68" x2="68" y2="40" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#2EC8FF"/><stop offset="1" stop-color="#FF2E8C"/></linearGradient>
<radialGradient id="gc" cx="54" cy="54" r="6" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFFFFF"/><stop offset=".45" stop-color="#FFC4E0"/><stop offset=".8" stop-color="#FF2E8C" stop-opacity=".9"/><stop offset="1" stop-color="#FF2E8C" stop-opacity="0"/></radialGradient>'''
def svg_layers():
    out='<circle cx="54" cy="54" r="34" fill="url(#halo)"/>\n'
    for d,s,w,o,f in layers:
        if f: out+='<path d="%s" fill="%s"/>\n'%(d,f)
        else: out+='<path d="%s" fill="none" stroke="%s" stroke-width="%s" stroke-linejoin="round" stroke-opacity="%s"/>\n'%(d,s,w,o)
    return out
full='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">\n<defs>\n%s\n%s</defs>\n<rect width="108" height="108" fill="url(#bg)"/>\n%s</svg>\n'%(bg_svg,defs,svg_layers())
open('icon.svg','w').write(full)
# Logo for Google / web: the same artwork cropped to the adaptive safe zone (centre 72 of 108), rounded.
logo='<svg xmlns="http://www.w3.org/2000/svg" viewBox="18 18 72 72" width="120" height="120">\n<defs>\n%s\n%s<clipPath id="rc"><rect x="18" y="18" width="72" height="72" rx="16"/></clipPath></defs>\n<g clip-path="url(#rc)"><rect width="108" height="108" fill="url(#bg)"/>\n%s</g></svg>\n'%(bg_svg,defs,svg_layers())
open('logo.svg','w').write(logo)
# Android vector drawables
def vg(body):
    return '<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    xmlns:aapt="http://schemas.android.com/aapt"\n    android:width="108dp" android:height="108dp"\n    android:viewportWidth="108" android:viewportHeight="108">\n%s</vector>\n'%body
def grad(kind, attrs, stops):
    items=''.join('                <item android:offset="%s" android:color="%s" />\n'%(o,c) for o,c in stops)
    return '            <gradient android:type="%s" %s>\n%s            </gradient>\n'%(kind,attrs,items)
def a(alpha,hex6): return '#%02X%s'%(round(alpha*255),hex6)
GO=lambda al: grad('linear','android:startX="30" android:startY="28" android:endX="78" android:endY="80"',[(0,a(al,'FF2E8C')),(0.55,a(al,'B05CFF')),(1,a(al,'2EC8FF'))])
GI=lambda al: grad('linear','android:startX="40" android:startY="68" android:endX="68" android:endY="40"',[(0,a(al,'2EC8FF')),(1,a(al,'FF2E8C'))])
fg='    <!-- Soft violet halo behind the rings -->\n    <path android:pathData="M54,54 m-34,0 a34,34 0 1,0 68,0 a34,34 0 1,0 -68,0">\n        <aapt:attr name="android:fillColor">\n'+grad('radial','android:centerX="54" android:centerY="54" android:gradientRadius="34"',[(0,'#597B3CFF'),(0.55,'#1F7B3CFF'),(1,'#007B3CFF')])+'        </aapt:attr>\n    </path>\n'
for d,s,w,o,f in layers:
    if f:
        fg+='    <path android:pathData="%s">\n        <aapt:attr name="android:fillColor">\n'%d+grad('radial','android:centerX="54" android:centerY="54" android:gradientRadius="6"',[(0,'#FFFFFFFF'),(0.45,'#FFFFC4E0'),(0.8,'#E6FF2E8C'),(1,'#00FF2E8C')])+'        </aapt:attr>\n    </path>\n'
    else:
        g=GO(o) if s=='url(#go)' else GI(o)
        fg+='    <path android:strokeWidth="%s" android:strokeLineJoin="round"\n        android:pathData="%s">\n        <aapt:attr name="android:strokeColor">\n%s        </aapt:attr>\n    </path>\n'%(w,d,g)
open('ic_launcher_fg.xml','w').write(vg(fg))
bg='    <path android:pathData="M0,0h108v108h-108z">\n        <aapt:attr name="android:fillColor">\n'+grad('radial','android:centerX="54" android:centerY="46" android:gradientRadius="70"',[(0,'#FF1C0B38'),(1,'#FF06040C')])+'        </aapt:attr>\n    </path>\n'
open('ic_launcher_bg.xml','w').write(vg(bg))
# Monochrome (themed icons): solid shapes only, the launcher tints it.
mono='    <path android:strokeWidth="3.4" android:strokeLineJoin="round" android:strokeColor="#FFFFFFFF" android:pathData="%s" />\n    <path android:strokeWidth="2.4" android:strokeLineJoin="round" android:strokeColor="#FFFFFFFF" android:pathData="%s" />\n    <path android:fillColor="#FFFFFFFF" android:pathData="%s" />\n'%(outer,inner,core)
open('ic_launcher_mono.xml','w').write(vg(mono))
print('ok')
