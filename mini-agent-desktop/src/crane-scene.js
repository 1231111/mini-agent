/* 启动页上的丹顶鹤（飞行姿态）。
   轨迹受相机视锥约束，启动结束时留在画面内渐隐。
   全程用 three.js 基础几何体生成，不依赖外部模型文件：
     - 躯干：球体顶点按尾端收窄，避免「一个球加一个盒子」的玩偶感
     - 脖颈：沿 CatmullRom 曲线扫出的渐细管（真实鹤飞行时颈部前伸成 S 形）
     - 翅膀：三层羽片（覆羽 / 次级飞羽 / 初级飞羽），每片带下垂、收尖和扭转
     - 腿：飞行时向后伸直，细长渐细
   路径缓慢环绕并受视锥约束。window.craneScene.burst() 原位淡出。 */
(function () {
  var canvas = document.getElementById('c');
  if (!canvas || !window.THREE) {
    window.craneScene = {
      burst: function () { return Promise.resolve(); }
    };
    return;
  }

  var THREE = window.THREE;
  var reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var renderer = new THREE.WebGLRenderer({ canvas: canvas, alpha: true, antialias: true });
  renderer.setClearColor(0x000000, 0);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  var scene = new THREE.Scene();
  var camera = new THREE.PerspectiveCamera(38, 1, 0.1, 40);
  camera.position.set(0, 0.35, 9.2);

  scene.add(new THREE.HemisphereLight(0xf8fcff, 0x313b48, 1.25));
  var key = new THREE.DirectionalLight(0xffffff, 1.28);
  key.position.set(3.5, 6, 4);
  scene.add(key);
  var rim = new THREE.DirectionalLight(0xc4d8e6, 0.42);
  rim.position.set(-4, 2, -3);
  scene.add(rim);
  /* 腹部补光：相机基本与鹤同高，近侧翼看到的全是翼下表面。
     这一盏太弱时，近侧翼会被压成一整块黑 —— 远看就是「一只黑翅膀一只白翅膀」。 */
  var fill = new THREE.DirectionalLight(0xdfefff, 0.8);
  fill.position.set(0, -4, 2.5);
  scene.add(fill);
  // 左侧补光：鹤的航向是斜的，只有右侧主光时，靠画面左边的那只翼整个背光
  var sideFill = new THREE.DirectionalLight(0xdcefff, 0.5);
  sideFill.position.set(-5, 1.5, 3.5);
  scene.add(sideFill);

  var white = new THREE.MeshStandardMaterial({
    color: 0xf4f8fa, roughness: 0.72, metalness: 0.0, side: THREE.DoubleSide, transparent: true
  });
  var whiteDim = new THREE.MeshStandardMaterial({
    color: 0xe6edf4, roughness: 0.7, metalness: 0.0, side: THREE.DoubleSide, transparent: true
  });
  var black = new THREE.MeshStandardMaterial({
    color: 0x14181d, roughness: 0.68, metalness: 0.02, side: THREE.DoubleSide, transparent: true
  });
  var red = new THREE.MeshStandardMaterial({ color: 0xc41f2b, roughness: 0.42, metalness: 0.05, transparent: true });
  var beakMat = new THREE.MeshStandardMaterial({ color: 0x7c8a6b, roughness: 0.5, metalness: 0.06, transparent: true });
  var legMat = new THREE.MeshStandardMaterial({ color: 0x22282c, roughness: 0.6, metalness: 0.05, transparent: true });

  /** 沿曲线扫出的渐细管：TubeGeometry 半径恒定，做不出鹤颈由粗到细的过渡。 */
  function taperedTube(curve, segments, radial, radiusFn) {
    var frames = curve.computeFrenetFrames(segments, false);
    var pos = [], nor = [], uv = [], idx = [];
    var i, j;
    for (i = 0; i <= segments; i++) {
      var t = i / segments;
      var p = curve.getPointAt(t);
      var r = radiusFn(t);
      for (j = 0; j <= radial; j++) {
        var v = j / radial * Math.PI * 2;
        var sin = Math.sin(v);
        var cos = -Math.cos(v);
        var n = new THREE.Vector3(
          cos * frames.normals[i].x + sin * frames.binormals[i].x,
          cos * frames.normals[i].y + sin * frames.binormals[i].y,
          cos * frames.normals[i].z + sin * frames.binormals[i].z
        ).normalize();
        pos.push(p.x + r * n.x, p.y + r * n.y, p.z + r * n.z);
        nor.push(n.x, n.y, n.z);
        uv.push(t, j / radial);
      }
    }
    for (i = 1; i <= segments; i++) {
      for (j = 1; j <= radial; j++) {
        var a = (radial + 1) * (i - 1) + (j - 1);
        var b = (radial + 1) * i + (j - 1);
        var c = (radial + 1) * i + j;
        var d = (radial + 1) * (i - 1) + j;
        idx.push(a, b, d, b, c, d);
      }
    }
    var g = new THREE.BufferGeometry();
    g.setIndex(idx);
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    return g;
  }

  /**
   * 单根羽片：沿 +X 展开，宽度向尖端收窄，整片带下垂（bend）与绕轴扭转（twist）。
   * 旧实现用 BoxGeometry，羽毛是等宽矩形平板 —— 这是「木偶感」最主要的来源。
   */
  function featherGeometry(len, width, bend, twist) {
    var segs = 8;
    var pos = [], uv = [], idx = [];
    var i;
    for (i = 0; i <= segs; i++) {
      var t = i / segs;
      var x = len * t;
      var y0 = -bend * t * t;
      var half = width * 0.5 * (1 - 0.82 * Math.pow(t, 1.5));
      var camber = 0.12 * width * Math.sin(Math.PI * t);
      var tw = twist * t;
      var c = Math.cos(tw), s = Math.sin(tw);
      var yA = y0 + camber, zA = -half;
      var yB = y0 + camber, zB = half;
      pos.push(x, yA * c - zA * s, yA * s + zA * c);
      pos.push(x, yB * c - zB * s, yB * s + zB * c);
      uv.push(t, 0, t, 1);
    }
    for (i = 0; i < segs; i++) {
      var a = i * 2, b = i * 2 + 1, c2 = i * 2 + 2, d = i * 2 + 3;
      idx.push(a, b, c2, b, d, c2);
    }
    var g = new THREE.BufferGeometry();
    g.setIndex(idx);
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.computeVertexNormals();
    return g;
  }

  function addFeather(parent, opts) {
    var mesh = new THREE.Mesh(
      featherGeometry(opts.len, opts.width, opts.bend || 0, opts.twist || 0),
      opts.mat
    );
    mesh.position.set(opts.x || 0, opts.y || 0, opts.z || 0);
    mesh.rotation.y = opts.sweep || 0;
    mesh.rotation.z = opts.roll || 0;
    parent.add(mesh);
    return mesh;
  }

  /** 翼面材质：颜色由顶点色给出（翼根白、后缘与翼尖转黑），比拿两块材质拼接不出缝。 */
  var wingMat = new THREE.MeshStandardMaterial({
    vertexColors: true, roughness: 0.66, metalness: 0.0, side: THREE.DoubleSide, transparent: true
  });

  /** 体表羽片材质：比翼面稍暖一点，靠明暗差把羽毛的层次显出来。 */
  var plumageMat = new THREE.MeshStandardMaterial({
    color: 0xf4f8fc, roughness: 0.78, metalness: 0.0, side: THREE.DoubleSide, transparent: true
  });

  /**
   * 把一堆羽片烘焙进同一个几何体。
   * 一片羽片 9 段两列共 20 个顶点，几十片如果各建一个 Mesh 就是几十次 draw call；
   * 合成一个几何体后只剩一次。
   */
  function bakePlumage(items) {
    var pos = [], nor = [], uv = [], idx = [], base = 0;
    var i, j;
    for (i = 0; i < items.length; i++) {
      var it = items[i];
      var g = featherGeometry(it.len, it.width, 0, it.twist || 0);
      g.applyMatrix4(it.matrix);
      g.computeVertexNormals();
      var p = g.attributes.position;
      var n = g.attributes.normal;
      for (j = 0; j < p.count; j++) {
        pos.push(p.getX(j), p.getY(j), p.getZ(j));
        nor.push(n.getX(j), n.getY(j), n.getZ(j));
        uv.push(0, 0);
      }
      var ix = g.index.array;
      for (j = 0; j < ix.length; j++) {
        idx.push(ix[j] + base);
      }
      base += p.count;
      g.dispose();
    }
    var out = new THREE.BufferGeometry();
    out.setIndex(idx);
    out.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    out.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
    out.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    return out;
  }

  /**
   * 体表羽片：沿躯干顶点铺一层短羽，全部贴着体表、朝后（+Z）倒伏。
   * 没有这一层，躯干就是一个光滑的白色椭球 —— 那是「木偶感」最后剩下的部分。
   */
  function makeBodyPlumage(bodyGeo) {
    var p = bodyGeo.attributes.position;
    var nrm = bodyGeo.attributes.normal;
    var items = [];
    var d = new THREE.Vector3();
    var n = new THREE.Vector3();
    var xa = new THREE.Vector3();
    var ya = new THREE.Vector3();
    var za = new THREE.Vector3();
    var m = new THREE.Matrix4();
    var i;
    var step = 7;                       // 每 7 个顶点铺一片，密度约 110 片
    for (i = 0; i < p.count; i += step) {
      n.set(nrm.getX(i), nrm.getY(i), nrm.getZ(i)).normalize();
      // 把 +Z 投影到该点的切平面上，得到「朝后」的切向
      d.set(0, 0, 1).addScaledVector(n, -n.z);
      if (d.lengthSq() < 1e-4) {
        continue;
      }
      d.normalize();
      xa.copy(d);
      ya.copy(n);
      za.crossVectors(xa, ya).normalize();
      ya.crossVectors(za, xa).normalize();
      m.makeBasis(xa, ya, za);
      m.setPosition(
        p.getX(i) + n.x * 0.002,
        p.getY(i) + n.y * 0.002,
        p.getZ(i) + n.z * 0.002
      );
      items.push({
        len: 0.095 + (i % 5) * 0.011,
        width: 0.058,
        matrix: m.clone()
      });
    }
    return bakePlumage(items);
  }

  /**
   * 翼面：一整片带拱度的升力面。
   *   s 沿翼展 0→1，c 沿弦向 0（前缘）→1（后缘）。
   * 真实鸟翼不是一排独立羽片，而是一整片曲面，只有后缘露出羽尖；
   * 旧实现把羽片当主体，从侧面看就是一排黑棍。
   */
  function wingPanel(len, chordRoot, chordTip, opts) {
    opts = opts || {};
    var ns = 20, nc = 7;
    var pos = [], col = [], uv = [], idx = [];
    var i, j;
    var cWhite = new THREE.Color(0xfbfdff);
    var cBlack = new THREE.Color(0x151a20);
    var tmp = new THREE.Color();
    for (i = 0; i <= ns; i++) {
      var s = i / ns;
      var chord = chordRoot + (chordTip - chordRoot) * Math.pow(s, 0.9);
      var leZ = (opts.leStart || 0) - (opts.leSweep || 0) * Math.pow(s, 1.7);
      var x = len * s;
      for (j = 0; j <= nc; j++) {
        var c = j / nc;
        var z = leZ + chord * c;
        var y = (opts.camber || 0.05) * Math.sin(Math.PI * c) * (1 - 0.45 * s)
              + (opts.dihedral || 0.03) * s
              - (opts.droop || 0) * s * s;
        pos.push(x, y, z);
        uv.push(s, c);
        var k = Math.min(1, Math.max(0, (c - 0.56) / 0.34));
        // 黑色只给后缘一窄条 + 翼尖：旧版从弦长 38% 就开始转黑，
        // 翼面被透视压缩之后近侧翼会变成一整块黑楔子，看着像鹅。
        var blackness = Math.max(k, (opts.tipBlack || 0) * Math.pow(s, 3));
        tmp.copy(cWhite).lerp(cBlack, Math.min(1, blackness));
        col.push(tmp.r, tmp.g, tmp.b);
      }
    }
    for (i = 0; i < ns; i++) {
      for (j = 0; j < nc; j++) {
        var a = (nc + 1) * i + j;
        var b = (nc + 1) * (i + 1) + j;
        idx.push(a, b, a + 1, b, b + 1, a + 1);
      }
    }
    var g = new THREE.BufferGeometry();
    g.setIndex(idx);
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
    g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3));
    g.computeVertexNormals();
    return g;
  }

  /**
   * 一只翅膀：肩 → 腕两段关节。
   * 羽片自内向外分三层，长度递增、颜色从白过渡到黑，模拟丹顶鹤的翼面。
   */
  function makeWing(side) {
    var root = new THREE.Group();
    root.userData.side = side;

    var upper = new THREE.Group();
    upper.name = 'upper';
    root.add(upper);

    // 翼臂（肩→腕）：控制翼展，不让翼面把身体压成一条细线。
    // 适当增加弦长和拱度，画面中能看到完整翼面，而不是两片薄长板。
    var arm = new THREE.Mesh(wingPanel(0.56, 0.74, 0.54, {
      leSweep: 0.04, camber: 0.10, dihedral: 0.13, droop: 0.02, tipBlack: 0
    }), wingMat);
    upper.add(arm);

    var lower = new THREE.Group();
    lower.name = 'lower';
    lower.position.x = 0.56;
    upper.add(lower);

    // 手部（腕→翼尖）：弦长 0.52 → 0.20，前缘后掠并自然下垂。
    var hand = new THREE.Mesh(wingPanel(0.46, 0.54, 0.22, {
      leStart: -0.05, leSweep: 0.18,
      camber: 0.07, dihedral: 0.11, droop: 0.045, tipBlack: 1
    }), wingMat);
    lower.add(hand);

    var i;
    // 初级飞羽：挂在手部后缘上（翼尖弦长只剩 0.16，后缘在 z≈0，不是 z=0.14）。
    // 挂错位置会让翼尖出现两块悬空的黑片 —— 第一版就是这样。
    // 长度、宽度都做微差，避免排成整齐的一排黑齿。
    var primaries = [];
    for (i = 0; i < 9; i++) {
      var pt = i / 8;
      var f = addFeather(lower, {
        len: 0.24 + pt * 0.22 + (i % 2) * 0.02,
        width: 0.1 + (i % 3) * 0.014, mat: black,
        x: 0.46 + pt * 0.26, y: -0.008, z: 0.02,
        sweep: -Math.PI / 2 + 0.12 + pt * 0.3,
        roll: (i % 2 ? 1 : -1) * 0.03, bend: 0.05, twist: -0.18
      });
      primaries.push(f);
    }
    lower.userData.primaries = primaries;

    root.userData.upper = upper;
    root.userData.lower = lower;
    return root;
  }

  function makeBody() {
    var geo = new THREE.SphereGeometry(0.40, 32, 24);
    var p = geo.attributes.position;
    var v = new THREE.Vector3();
    var i;
    for (i = 0; i < p.count; i++) {
      v.fromBufferAttribute(p, i);
      var t = (v.z / 0.30 + 1) * 0.5;        // 0=胸部（-Z），1=尾部（+Z）
      var taper = 1 - 0.55 * t;              // 尾端收窄：胸部最宽，向后收到尾根
      // 鹤的躯干很窄：宽约 0.37、高约 0.35，长 1.05（旧版宽高都到 0.68，侧看像条鱼）
      v.x *= 0.58 * taper + 0.15;
      v.y *= 0.55 * taper + 0.17;
      v.z *= 1.72;
      // 胸部略上提，模仿飞行时的龙骨突出
      v.y += 0.02 * Math.max(0, 1 - Math.abs(v.z + 0.15) * 3);
      p.setXYZ(i, v.x, v.y, v.z);
    }
    geo.computeVertexNormals();
    return new THREE.Mesh(geo, white);
  }

  function makeNeckAndHead() {
    var g = new THREE.Group();

    // 飞行姿态：颈前伸，S 形。控制点自胸部向头部收拢。
    var curve = new THREE.CatmullRomCurve3([
      new THREE.Vector3(0, 0.16, -0.36),
      new THREE.Vector3(0, 0.24, -0.60),
      new THREE.Vector3(0, 0.22, -0.82),
      new THREE.Vector3(0, 0.19, -0.96)
    ]);
    // 丹顶鹤的颈部不是整段黑色：下颈与胸部相接是白色，上半段才是黑色。
    // 两段曲线略微重叠，避免飞行姿态变化时在颜色交界处露出缝隙。
    var throatCurve = new THREE.CatmullRomCurve3([
      curve.getPointAt(0), curve.getPointAt(0.38), curve.getPointAt(0.53)
    ]);
    var throat = new THREE.Mesh(
      taperedTube(throatCurve, 14, 14, function (t) { return 0.098 - 0.022 * t; }),
      white
    );
    g.add(throat);
    var neckCurve = new THREE.CatmullRomCurve3([
      curve.getPointAt(0.45), curve.getPointAt(0.7), curve.getPointAt(1)
    ]);
    var neck = new THREE.Mesh(
      taperedTube(neckCurve, 16, 14, function (t) { return 0.092 - 0.032 * t; }),
      black
    );
    g.add(neck);

    var head = new THREE.Group();
    head.position.set(0, 0.19, -1.00);

    var skull = new THREE.Mesh(new THREE.SphereGeometry(0.11, 24, 18), white);
    skull.scale.set(0.98, 0.92, 1.22);
    head.add(skull);

    // 丹顶：真实位置在头顶偏后，扁平一块，不是一个小球
    var crown = new THREE.Mesh(new THREE.SphereGeometry(0.092, 20, 14), red);
    crown.scale.set(0.98, 0.36, 1.08);
    crown.position.set(0, 0.082, 0.01);
    head.add(crown);

    // 眼周裸皮（深色），让头部不是一个纯白球
    var mask = new THREE.Mesh(new THREE.SphereGeometry(0.088, 18, 12), black);
    mask.scale.set(0.76, 0.44, 0.62);
    mask.position.set(0, -0.006, -0.035);
    head.add(mask);

    var beak = new THREE.Mesh(
      taperedTube(new THREE.CatmullRomCurve3([
        new THREE.Vector3(0, 0, -0.055),
        new THREE.Vector3(0, -0.008, -0.25),
        new THREE.Vector3(0, -0.032, -0.46)
      ]), 10, 8, function (t) {
        return 0.024 * (1 - 0.92 * t);
      }),
      beakMat
    );
    head.add(beak);

    var eyeGeo = new THREE.SphereGeometry(0.017, 10, 10);
    var eyeL = new THREE.Mesh(eyeGeo, black);
    eyeL.position.set(0.045, 0.026, -0.060);
    var eyeR = eyeL.clone();
    eyeR.position.x = -0.033;
    head.add(eyeL);
    head.add(eyeR);

    g.add(head);
    g.userData.head = head;
    return g;
  }

  /**
   * 整只鹤。内部按「头朝 -Z、尾朝 +Z」建模（跟解剖学描述一致，读代码时不易搞反），
   * 最外层再绕 Y 转 180°，因为 three.js 的 Object3D.lookAt 让**局部 +Z** 指向目标。
   * 少了这一次翻转，整只鸟就是尾巴朝前飞的 —— 旧版一直是这样。
   */
  function makeCrane() {
    var g = new THREE.Group();
    var body = makeBody();
    g.add(body);
    g.add(new THREE.Mesh(makeBodyPlumage(body.geometry), plumageMat));
    var neckHead = makeNeckAndHead();
    g.add(neckHead);

    // 尾羽：短扇形，白色带黑边。羽片几何沿 +X 生长，-PI/2 转到 +Z（正后方）。
    // z 取 0.5 靠在躯干尾端（躯干半长 0.525），再往外就是腿，太靠里会被躯干完全盖住。
    var i;
    for (i = 0; i < 9; i++) {
      var tt = i / 8 - 0.5;
      addFeather(g, {
        len: 0.36, width: 0.1, mat: i > 6 ? black : white,
        x: 0, y: 0.03, z: 0.5,
        sweep: -Math.PI / 2 - tt * 0.55, roll: tt * 0.3, bend: 0.03
      });
    }

    // 腿：飞行时向后伸直，细长且略高于体轴
    var s;
    for (s = -1; s <= 1; s += 2) {
      var leg = new THREE.Mesh(
        taperedTube(new THREE.CatmullRomCurve3([
          new THREE.Vector3(s * 0.055, -0.075, 0.2),
          new THREE.Vector3(s * 0.05, -0.065, 0.62),
          new THREE.Vector3(s * 0.045, -0.06, 1.02)
        ]), 14, 8, function (t) {
          return 0.015 - 0.007 * t;
        }),
        legMat
      );
      g.add(leg);
      // 趾：向后收拢的三根细条
      var k;
      for (k = -1; k <= 1; k++) {
        var toe = new THREE.Mesh(
          taperedTube(new THREE.CatmullRomCurve3([
            new THREE.Vector3(s * 0.045, -0.06, 1.02),
            new THREE.Vector3(s * 0.045 + k * 0.03, -0.075, 1.12),
            new THREE.Vector3(s * 0.045 + k * 0.05, -0.09, 1.2)
          ]), 6, 6, function () { return 0.006; }),
          legMat
        );
        g.add(toe);
      }
    }

    var wingL = makeWing(1);
    var wingR = makeWing(-1);
    // 翼根抬高并向后错开，露出黑颈、头部和白色胸部轮廓。
    wingL.position.set(0.07, 0.25, 0.16);
    wingR.position.set(-0.07, 0.25, 0.16);
    // 负缩放做镜像；羽片材质是 DoubleSide，背面光照由 gl_FrontFacing 处理
    wingR.scale.x = -1;
    g.add(wingL);
    g.add(wingR);

    var root = new THREE.Group();
    root.add(g);
    g.rotation.y = Math.PI;
    root.userData.wings = [wingL, wingR];
    root.userData.head = neckHead.userData.head;
    return root;
  }

  var crane = makeCrane();
  scene.add(crane);

  var speckGeo = new THREE.BufferGeometry();
  var speckPos = new Float32Array(48 * 3);
  speckGeo.setAttribute('position', new THREE.BufferAttribute(speckPos, 3));
  var specks = new THREE.Points(speckGeo, new THREE.PointsMaterial({
    color: 0xe8fbff,
    size: 0.06,
    transparent: true,
    opacity: 0
  }));
  scene.add(specks);
  var speckDir = [];
  var i;
  for (i = 0; i < 48; i++) {
    var a = Math.random() * Math.PI * 2;
    var b = Math.random() * 0.6 - 0.3;
    speckDir.push(new THREE.Vector3(Math.cos(a), b, Math.sin(a)));
  }

  var flash = new THREE.Mesh(
    new THREE.PlaneGeometry(8, 8),
    new THREE.MeshBasicMaterial({
      color: 0xf4fdff,
      transparent: true,
      opacity: 0,
      depthTest: false
    })
  );
  flash.position.z = -1.05;
  flash.renderOrder = 10;
  camera.add(flash);
  scene.add(camera);

  /* 鹤只在相机可视区中段缓慢环绕。相机竖向视野会随窗口比例改变，
     动态边界在 steer() 中按当前相机视锥计算，并扣除翼展安全余量。 */
  var pos = new THREE.Vector3(-0.25, 0.72, 0.2);
  var vel = new THREE.Vector3(0.22, -0.02, -0.35);
  var target = new THREE.Vector3(0.25, 0.72, -0.35);
  var speed = 0.58;
  var orbitPhase = 0;
  var glide = 0;
  var flapPhase = 0;
  var bursting = false;
  var burstAt = 0;
  /* 由测试页接管渲染时置位：停掉自身 rAF 循环，避免它把相机和姿态改回去。 */
  var frozen = false;
  var BURST_MS = 1400;
  var clock = new THREE.Clock();
  var desired = new THREE.Vector3();
  var bank = 0;

  function viewHalfExtents(z) {
    var depth = camera.position.z - z;
    var halfHeight = Math.tan(THREE.MathUtils.degToRad(camera.fov * 0.5)) * depth;
    return { x: halfHeight * camera.aspect, y: halfHeight };
  }

  function modelHalfExtents() {
    // 插画动画不做真实 3D 转向，Box3 在根组上与实际旋转尺寸一致。
    crane.updateMatrixWorld(true);
    var bounds = new THREE.Box3().setFromObject(crane);
    var size = bounds.getSize(new THREE.Vector3());
    return { x: size.x * 0.5, y: size.y * 0.5 };
  }

  function pickTarget() {
    var half = viewHalfExtents(-0.4);
    var marginX = Math.min(2.2, half.x * 0.58);
    var marginY = Math.min(1.8, half.y * 0.58);
    return new THREE.Vector3(
      (Math.random() * 2 - 1) * Math.max(0.2, half.x - marginX),
      (Math.random() * 2 - 1) * Math.max(0.15, half.y - marginY),
      -0.85 + Math.random() * 0.9
    );
  }

  function resize() {
    var w = window.innerWidth;
    var h = window.innerHeight;
    if (w < 1 || h < 1) {
      return;
    }
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    renderer.setSize(w, h, false);
    crane.updateMatrixWorld(true);
    var bounds = new THREE.Box3().setFromObject(crane);
    var size = bounds.getSize(new THREE.Vector3());
    var half = viewHalfExtents(0.2);
    var fitX = (half.x - 0.12) / Math.max(0.01, size.x * 0.5);
    var fitY = (half.y * 0.82 - 0.12) / Math.max(0.01, size.y * 0.5);
    crane.scale.setScalar(THREE.MathUtils.clamp(Math.min(1.32, fitX, fitY), 0.34, 1.32));
  }
  window.addEventListener('resize', resize);
  resize();

  function steer(dt) {
    var half = viewHalfExtents(pos.z);
    var bounds = modelHalfExtents();
    var safety = 0.12;
    var limitX = Math.max(0, half.x - bounds.x - safety);
    var limitY = Math.max(0, half.y - bounds.y - safety);

    if (!bursting) {
      orbitPhase += dt * 0.32;
      var orbitX = Math.cos(orbitPhase) * limitX * 0.48;
      var orbitY = limitY * 0.36 + Math.sin(orbitPhase * 1.35) * limitY * 0.2;
      if (pos.distanceTo(target) < 0.3) {
        target = pickTarget();
      }
      target.x = THREE.MathUtils.clamp(target.x, -limitX * 0.6, limitX * 0.6);
      target.y = THREE.MathUtils.clamp(target.y, -limitY * 0.6, limitY * 0.6);
      target.z = -0.35 + Math.sin(orbitPhase * 0.7) * 0.38;
      target.x = THREE.MathUtils.lerp(target.x, orbitX, 0.025);
      target.y = THREE.MathUtils.lerp(target.y, orbitY, 0.025);
    }

    desired.copy(target).sub(pos);
    if (desired.lengthSq() < 1e-4) return;
    desired.normalize().multiplyScalar(bursting ? 0.42 : speed);
    desired.sub(vel);
    var maxTurn = (bursting ? 1.8 : 1.25) * dt;
    if (desired.length() > maxTurn) desired.setLength(maxTurn);
    var fx = vel.x, fz = vel.z;
    var tx = target.x - pos.x, tz = target.z - pos.z;
    bank = THREE.MathUtils.clamp(-(fx * tz - fz * tx) * 0.12, -0.28, 0.28);
    vel.add(desired);
    var maxSpeed = bursting ? 0.55 : speed;
    if (vel.length() > maxSpeed) vel.setLength(maxSpeed);
    pos.addScaledVector(vel, dt);

    // 用真实视锥而非固定世界坐标限位；较宽余量覆盖翼展与转弯侧倾。
    half = viewHalfExtents(pos.z);
    bounds = modelHalfExtents();
    limitX = Math.max(0, half.x - bounds.x - safety);
    limitY = Math.max(0, half.y - bounds.y - safety);
    var clampedX = THREE.MathUtils.clamp(pos.x, -limitX, limitX);
    var clampedY = THREE.MathUtils.clamp(pos.y, -limitY, limitY);
    if (clampedX !== pos.x || clampedY !== pos.y) {
      pos.x = clampedX;
      pos.y = clampedY;
      vel.x *= 0.35;
      vel.y *= 0.35;
    }
    pos.z = THREE.MathUtils.clamp(pos.z, -0.6, 0.45);
  }

  /**
   * 振翅：肩关节主摆，腕关节滞后半个相位（真实鸟类的前臂不会与肩同步），
   * 初级飞羽再随拍打轻微弯曲，翼面因此不是一块硬板。
   *
   * 两侧必须用**同一个** rotation.z：右翼是靠 scale.x=-1 镜像出来的，
   * 镜像只翻 x、不翻 y，所以同一个 z 转角在两侧正好变成对称的上扬。
   * 旧版乘了 -side，结果一只向上、一只向下 —— 从上方看像断了半边翅膀。
   */
  function flapWings(amp) {
    var beat = Math.sin(flapPhase);
    var lag = Math.sin(flapPhase - 0.55);
    var wings = crane.userData.wings;
    var w;
    for (w = 0; w < wings.length; w++) {
      var upper = wings[w].userData.upper;
      var lower = wings[w].userData.lower;
      upper.rotation.z = 0.12 + beat * amp;
      upper.rotation.y = beat * 0.06;
      lower.rotation.z = 0.08 + lag * amp * 0.4;
      // -0.12 是洗流扭转（washout）：外翼前缘略微下压，翼面因此不是纯平面。
      // 顺带解决一个纯几何问题 —— 相机沿 -Z 看、翼弦也朝 +Z 时，
      // 翼面法线与视线垂直，不带扭转就永远只剩一条线。
      lower.rotation.x = -0.12 + lag * amp * 0.1;
      var ps = lower.userData.primaries;
      var k;
      for (k = 0; k < ps.length; k++) {
        ps[k].rotation.z = Math.sin(flapPhase - 0.9 - k * 0.12) * amp * 0.12;
      }
    }
  }

  function draw() {
    if (frozen) {
      return;
    }
    var dt = Math.min(0.05, clock.getDelta());
    var now = performance.now();
    var u = 0;
    if (bursting) {
      u = Math.min(1, (now - burstAt) / BURST_MS);
      target.set(0.35, -0.08, -0.2);
    }
    steer(dt);
    crane.position.copy(pos);
    crane.position.y += Math.sin(flapPhase) * 0.035;
    // 只按水平航向转向。lookAt(ahead) 会把整个模型俯仰到速度向量上；
    // 垂直速度稍大时，固定镜头就会看到鹤的正面/背面，身体竖起、翼展横铺。
    // 这里保持机身近似水平，仅保留小幅侧倾，避免轨迹改变时姿态翻转。
    crane.rotation.set(0, Math.atan2(vel.x, Math.max(0.08, Math.abs(vel.z))), 0);
    crane.rotateZ(bank);

    // 收尾时只淡出，不加速冲向镜头，鹤始终留在启动画布的安全区内。
    var fade = bursting ? 1 - THREE.MathUtils.smoothstep(u, 0.35, 1) : 1;
    crane.visible = fade > 0.005;
    if (bursting) {
      crane.traverse(function (part) {
        if (!part.material) return;
        var materials = Array.isArray(part.material) ? part.material : [part.material];
        for (var m = 0; m < materials.length; m++) {
          materials[m].opacity = fade;
        }
      });
    }
    var amp = reduced ? 0.08 : (bursting ? 0.16 * (1 - u) : 0.18);
    flapPhase += dt * (reduced ? 1.5 : 5.2);
    flapWings(amp);

    if (u > 0.42) {
      var life = (u - 0.42) / 0.58;
      var p;
      for (p = 0; p < 48; p++) {
        speckPos[p * 3] = pos.x + speckDir[p].x * life * 3.2;
        speckPos[p * 3 + 1] = pos.y + speckDir[p].y * life * 3.2;
        speckPos[p * 3 + 2] = pos.z + speckDir[p].z * life * 3.2;
      }
      speckGeo.attributes.position.needsUpdate = true;
      specks.material.opacity = 1 - life;
    }
    flash.material.opacity = u > 0.7 ? (u - 0.7) / 0.3 : 0;
    renderer.render(scene, camera);
    requestAnimationFrame(draw);
  }

  window.craneScene = {
    burst: function () {
      if (bursting) {
        return Promise.resolve();
      }
      bursting = true;
      burstAt = performance.now();
      return new Promise(function (resolve) {
        setTimeout(resolve, BURST_MS);
      });
    }
  };

  /* 无头浏览器验收出口。测试页在加载本文件之前设置 window.__CRANE_STUDIO__ 才会挂上；
     生产壳（Electron 启动页）不设置，这段不执行，也不会多出任何全局变量。 */
  if (window.__CRANE_STUDIO__) {
    window.__craneStudio = {
      scene: scene,
      renderer: renderer,
      camera: camera,
      crane: crane,
      stop: function () { frozen = true; },
      flap: function (phase, amp) {
        flapPhase = phase;
        flapWings(amp == null ? 0.24 : amp);
      },
      level: function (bankRad) {
        crane.rotation.set(0, 0, 0);
        if (bankRad) {
          crane.rotateZ(bankRad);
        }
      }
    };
  }

  requestAnimationFrame(draw);
})();
