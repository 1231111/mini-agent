/* 启动页和托盘浮层共用的全息场景。页面上要有 <canvas id="c">。 */
(function () {
  var canvas = document.getElementById('c');
  if (!canvas) {
    return;
  }
  var ctx = canvas.getContext('2d');
  var bars = [
    { label: '检索', h: 1.15 },
    { label: '思考', h: 2.35 },
    { label: '工具', h: 1.55 },
    { label: '回答', h: 2.75 },
    { label: '合计', h: 1.9 }
  ];
  var halfW = 0.28;
  var halfD = 0.28;
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  function resize() {
    var dpr = window.devicePixelRatio || 1;
    canvas.width = Math.floor(window.innerWidth * dpr);
    canvas.height = Math.floor(window.innerHeight * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', resize);
  resize();

  function project(p, yaw, pitch) {
    var cy = Math.cos(yaw);
    var sy = Math.sin(yaw);
    var x = p.x * cy + p.z * sy;
    var z = -p.x * sy + p.z * cy;
    var y = p.y;
    var cp = Math.cos(pitch);
    var sp = Math.sin(pitch);
    var y2 = y * cp - z * sp;
    var z2 = y * sp + z * cp;
    var depth = 7.4 - z2;
    var f = Math.min(window.innerWidth, window.innerHeight) * 0.72;
    var s = f / depth;
    return {
      x: window.innerWidth / 2 + x * s,
      y: window.innerHeight * 0.56 - y2 * s,
      z: z2
    };
  }

  function quad(pts, fill, stroke) {
    ctx.beginPath();
    ctx.moveTo(pts[0].x, pts[0].y);
    for (var i = 1; i < pts.length; i++) {
      ctx.lineTo(pts[i].x, pts[i].y);
    }
    ctx.closePath();
    ctx.fillStyle = fill;
    ctx.fill();
    if (stroke) {
      ctx.strokeStyle = stroke;
      ctx.lineWidth = 1.2;
      ctx.stroke();
    }
  }

  function drawGrid(yaw) {
    ctx.save();
    ctx.strokeStyle = 'rgba(80, 210, 255, 0.28)';
    ctx.lineWidth = 1;
    for (var i = -4; i <= 4; i++) {
      var a = project({ x: i * 0.55, y: 0, z: -2.4 }, yaw, 0.35);
      var b = project({ x: i * 0.55, y: 0, z: 2.4 }, yaw, 0.35);
      ctx.beginPath();
      ctx.moveTo(a.x, a.y);
      ctx.lineTo(b.x, b.y);
      ctx.stroke();
      var c = project({ x: -2.2, y: 0, z: i * 0.55 }, yaw, 0.35);
      var d = project({ x: 2.2, y: 0, z: i * 0.55 }, yaw, 0.35);
      ctx.beginPath();
      ctx.moveTo(c.x, c.y);
      ctx.lineTo(d.x, d.y);
      ctx.stroke();
    }
    ctx.restore();
  }

  function drawRings(yaw, t) {
    ctx.save();
    ctx.strokeStyle = 'rgba(120, 245, 255, 0.55)';
    ctx.lineWidth = 1.4;
    for (var ring = 0; ring < 2; ring++) {
      var rad = 2.15 + ring * 0.55;
      var bob = reduced ? 0 : Math.sin(t * 1.4 + ring) * 0.05;
      var lift = 0.08 + ring * 0.35 + bob;
      ctx.beginPath();
      for (var i = 0; i <= 64; i++) {
        var spin = reduced ? 0 : t * (ring === 0 ? 0.35 : -0.22);
        var ang = (i / 64) * Math.PI * 2 + spin;
        var p = project({
          x: Math.cos(ang) * rad,
          y: lift,
          z: Math.sin(ang) * rad * 0.42
        }, yaw, 0.35);
        if (i === 0) {
          ctx.moveTo(p.x, p.y);
        } else {
          ctx.lineTo(p.x, p.y);
        }
      }
      ctx.stroke();
    }
    ctx.restore();
  }

  function barFaces(bar, index) {
    var x = (index - 2) * 0.78;
    var h = bar.h;
    var corners = [
      { x: x - halfW, y: 0, z: -halfD },
      { x: x + halfW, y: 0, z: -halfD },
      { x: x + halfW, y: 0, z: halfD },
      { x: x - halfW, y: 0, z: halfD },
      { x: x - halfW, y: h, z: -halfD },
      { x: x + halfW, y: h, z: -halfD },
      { x: x + halfW, y: h, z: halfD },
      { x: x - halfW, y: h, z: halfD }
    ];
    var specs = [
      { ids: [0, 1, 5, 4], fill: 'rgba(40, 170, 210, 0.28)' },
      { ids: [1, 2, 6, 5], fill: 'rgba(70, 220, 255, 0.22)' },
      { ids: [3, 0, 4, 7], fill: 'rgba(20, 120, 160, 0.2)' },
      { ids: [4, 5, 6, 7], fill: 'rgba(170, 255, 255, 0.38)' }
    ];
    return specs.map(function (face) {
      return {
        pts: face.ids.map(function (id) { return corners[id]; }),
        fill: face.fill,
        label: bar.label,
        top: face.ids[0] === 4
      };
    });
  }

  function draw(now) {
    var w = window.innerWidth;
    var h = window.innerHeight;
    var t = reduced ? 0 : now / 1000;
    var yaw = reduced ? 0.6 : t * 0.35;
    ctx.clearRect(0, 0, w, h);

    var glow = ctx.createRadialGradient(w / 2, h * 0.56, 20, w / 2, h * 0.56, 280);
    glow.addColorStop(0, 'rgba(40, 180, 220, 0.18)');
    glow.addColorStop(1, 'rgba(40, 180, 220, 0)');
    ctx.fillStyle = glow;
    ctx.fillRect(0, 0, w, h);

    drawGrid(yaw);
    drawRings(yaw, t);

    var faces = [];
    bars.forEach(function (bar, i) {
      barFaces(bar, i).forEach(function (face) { faces.push(face); });
    });
    var drawn = faces.map(function (face) {
      var pts = face.pts.map(function (p) { return project(p, yaw, 0.35); });
      var z = 0;
      pts.forEach(function (p) { z += p.z; });
      return { pts: pts, fill: face.fill, top: face.top, label: face.label, z: z / pts.length };
    });
    drawn.sort(function (a, b) { return a.z - b.z; });
    drawn.forEach(function (face) {
      quad(face.pts, face.fill, 'rgba(190, 255, 255, 0.85)');
      if (!face.top) {
        return;
      }
      var cx = 0;
      var cy = 0;
      face.pts.forEach(function (p) {
        cx += p.x / 4;
        cy += p.y / 4;
      });
      ctx.fillStyle = 'rgba(210, 255, 255, 0.92)';
      ctx.font = '12px Segoe UI';
      ctx.textAlign = 'center';
      ctx.fillText(face.label, cx, cy - 10);
    });

    ctx.save();
    ctx.strokeStyle = 'rgba(180, 255, 230, 0.8)';
    ctx.lineWidth = 1.6;
    ctx.beginPath();
    bars.forEach(function (bar, i) {
      var bob = reduced ? 0 : Math.sin(t * 2 + i) * 0.08;
      var p = project({
        x: (i - 2) * 0.78,
        y: bar.h + 0.35 + bob,
        z: 0
      }, yaw, 0.35);
      if (i === 0) {
        ctx.moveTo(p.x, p.y);
      } else {
        ctx.lineTo(p.x, p.y);
      }
      ctx.fillStyle = '#e8ffff';
      ctx.fillRect(p.x - 2, p.y - 2, 4, 4);
    });
    ctx.stroke();
    ctx.restore();

    requestAnimationFrame(draw);
  }
  requestAnimationFrame(draw);
})();
