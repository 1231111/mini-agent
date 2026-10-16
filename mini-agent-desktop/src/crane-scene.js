/* 启动页上的丹顶鹤。飞时颈前伸、腿后伸，黑颈白体、丹顶、黑次级飞羽。
   路径是随机改航向，不是固定椭圆。window.craneScene.burst() 收势后炸开。 */
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

  scene.add(new THREE.HemisphereLight(0xf2fbff, 0x1a2838, 0.85));
  var key = new THREE.DirectionalLight(0xffffff, 1.35);
  key.position.set(3.5, 6, 4);
  scene.add(key);
  var rim = new THREE.DirectionalLight(0x9ae7ff, 0.45);
  rim.position.set(-4, 2, -3);
  scene.add(rim);

  var white = new THREE.MeshStandardMaterial({ color: 0xf7fbff, roughness: 0.55, metalness: 0 });
  var black = new THREE.MeshStandardMaterial({ color: 0x1a1e24, roughness: 0.62, metalness: 0 });
  var red = new THREE.MeshStandardMaterial({ color: 0xd52532, roughness: 0.4, metalness: 0.05 });
  var beakMat = new THREE.MeshStandardMaterial({ color: 0x6e8b82, roughness: 0.45, metalness: 0.08 });

  function feather(len, chord, mat, side) {
    var geo = new THREE.BoxGeometry(len, 0.015, chord);
    geo.translate(side * len / 2, 0, 0);
    return new THREE.Mesh(geo, mat);
  }

  function makeWing(side) {
    var root = new THREE.Group();
    var upper = new THREE.Group();
    upper.name = 'upper';
    var arm = feather(0.78, 0.26, white, side);
    arm.position.z = 0.02;
    upper.add(arm);

    var lower = new THREE.Group();
    lower.name = 'lower';
    lower.position.x = side * 0.78;
    var hand = feather(1.05, 0.18, white, side);
    lower.add(hand);
    var tip = feather(0.34, 0.12, black, side);
    tip.position.x = side * 1.02;
    tip.rotation.y = side * 0.25;
    lower.add(tip);
    var i;
    for (i = 0; i < 5; i++) {
      var sec = feather(0.62 - i * 0.06, 0.07, black, side);
      sec.position.set(side * 0.08, -0.01, 0.1 + i * 0.015);
      sec.rotation.y = side * (0.85 + i * 0.18);
      sec.rotation.z = -side * (0.15 + i * 0.04);
      lower.add(sec);
    }
    upper.add(lower);
    root.add(upper);
    root.userData.side = side;
    return root;
  }

  function makeCrane() {
    var g = new THREE.Group();
    var body = new THREE.Mesh(new THREE.SphereGeometry(0.32, 24, 18), white);
    body.scale.set(0.82, 0.68, 1.45);
    g.add(body);

    var tail = new THREE.Mesh(new THREE.BoxGeometry(0.18, 0.02, 0.2), white);
    tail.position.set(0, 0.04, 0.5);
    g.add(tail);

    var neck = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.078, 0.9, 14), black);
    neck.rotation.x = -Math.PI / 2;
    neck.position.set(0, 0.06, -0.7);
    g.add(neck);
    var nape = new THREE.Mesh(new THREE.BoxGeometry(0.055, 0.028, 0.72), white);
    nape.position.set(0, 0.125, -0.68);
    g.add(nape);

    var head = new THREE.Group();
    head.position.set(0, 0.08, -1.18);
    head.add(new THREE.Mesh(new THREE.SphereGeometry(0.092, 16, 12), white));
    var crown = new THREE.Mesh(new THREE.SphereGeometry(0.055, 12, 8), red);
    crown.scale.set(1, 0.42, 1);
    crown.position.y = 0.07;
    head.add(crown);
    var throat = new THREE.Mesh(new THREE.SphereGeometry(0.055, 10, 8), black);
    throat.position.set(0, -0.02, -0.04);
    head.add(throat);
    var eyeL = new THREE.Mesh(new THREE.SphereGeometry(0.012, 8, 8), black);
    eyeL.position.set(0.04, 0.02, -0.07);
    var eyeR = eyeL.clone();
    eyeR.position.x = -0.04;
    head.add(eyeL);
    head.add(eyeR);
    var beak = new THREE.Mesh(new THREE.ConeGeometry(0.026, 0.46, 8), beakMat);
    beak.rotation.x = Math.PI / 2;
    beak.position.z = -0.3;
    head.add(beak);
    g.add(head);

    var s;
    for (s = -1; s <= 1; s += 2) {
      var leg = new THREE.Mesh(new THREE.CylinderGeometry(0.012, 0.009, 0.82, 6), black);
      leg.rotation.x = Math.PI / 2;
      leg.position.set(s * 0.07, -0.16, 0.58);
      g.add(leg);
      var foot = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.012, 0.1), black);
      foot.position.set(s * 0.07, -0.16, 1.0);
      g.add(foot);
    }

    var wingL = makeWing(1);
    var wingR = makeWing(-1);
    wingL.position.set(0.08, 0.1, -0.02);
    wingR.position.set(-0.08, 0.1, -0.02);
    g.add(wingL);
    g.add(wingR);
    g.userData.wings = [wingL, wingR];
    return g;
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

  var pos = new THREE.Vector3(-3.2, 0.4, 0.5);
  var vel = new THREE.Vector3(1.6, 0.1, -0.4);
  var target = new THREE.Vector3(2.5, -0.2, -1);
  var speed = 2.1;
  var glide = 0;
  var flapPhase = 0;
  var bursting = false;
  var burstAt = 0;
  var BURST_MS = 1400;
  var clock = new THREE.Clock();
  var ahead = new THREE.Vector3();
  var desired = new THREE.Vector3();
  var up = new THREE.Vector3(0, 1, 0);
  var bank = 0;

  function pickTarget() {
    var choice = target;
    var n;
    for (n = 0; n < 8; n++) {
      choice = new THREE.Vector3(
        (Math.random() * 2 - 1) * 5.1,
        (Math.random() * 2 - 1) * 2.15,
        -2.4 + Math.random() * 5.2
      );
      if (choice.distanceTo(pos) > 3.2) {
        return choice;
      }
    }
    return choice;
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
  }
  window.addEventListener('resize', resize);
  resize();

  function steer(dt) {
    if (!bursting && pos.distanceTo(target) < 0.85) {
      target = pickTarget();
    }
    if (bursting) {
      desired.copy(target).sub(pos);
      if (desired.lengthSq() < 1e-4) {
        return;
      }
      desired.normalize().multiplyScalar(4.2);
      desired.sub(vel);
      var burstTurn = 6 * dt;
      if (desired.length() > burstTurn) {
        desired.setLength(burstTurn);
      }
      vel.add(desired);
      if (vel.length() > 4.6) {
        vel.setLength(4.6);
      }
      pos.addScaledVector(vel, dt);
      return;
    }
    if (Math.abs(pos.x) > 5.4) {
      target.x = -Math.sign(pos.x) * 2.2;
    }
    if (Math.abs(pos.y) > 2.35) {
      target.y = -Math.sign(pos.y) * 0.6;
    }
    if (pos.z > 3.4 || pos.z < -2.8) {
      target.z = pos.z > 0 ? -1.2 : 1.4;
    }
    desired.copy(target).sub(pos);
    if (desired.lengthSq() < 1e-4) {
      return;
    }
    desired.normalize().multiplyScalar(speed);
    desired.sub(vel);
    var maxTurn = 2.4 * dt;
    if (desired.length() > maxTurn) {
      desired.setLength(maxTurn);
    }
    var fx = vel.x;
    var fz = vel.z;
    var tx = target.x - pos.x;
    var tz = target.z - pos.z;
    bank = THREE.MathUtils.clamp(-(fx * tz - fz * tx) * 0.08, -0.65, 0.65);
    vel.add(desired);
    if (vel.length() > speed) {
      vel.setLength(speed);
    }
    if (vel.x * vel.x + vel.z * vel.z < 0.35) {
      vel.x += 0.4;
    }
    pos.addScaledVector(vel, dt);
  }

  function flapWings(amp) {
    var beat = Math.sin(flapPhase);
    var wings = crane.userData.wings;
    var w;
    for (w = 0; w < wings.length; w++) {
      var side = wings[w].userData.side;
      var upper = wings[w].getObjectByName('upper');
      var lower = upper.getObjectByName('lower');
      upper.rotation.z = -side * (0.2 + beat * amp);
      lower.rotation.z = -side * (0.12 + Math.max(-beat, 0) * amp * 0.55);
    }
  }

  function draw() {
    var dt = Math.min(0.05, clock.getDelta());
    var now = performance.now();
    var u = 0;
    if (bursting) {
      u = Math.min(1, (now - burstAt) / BURST_MS);
      target.set(0, 0.15, 5.5);
    } else if (glide <= 0 && !reduced && Math.random() < dt * 0.12) {
      glide = 0.45 + Math.random() * 0.9;
      speed = 1.45 + Math.random() * 1.35;
    }
    if (glide > 0) {
      glide -= dt;
    }
    steer(dt);
    crane.position.copy(pos);
    ahead.copy(pos).add(vel);
    crane.up.copy(up);
    crane.position.y += Math.sin(flapPhase) * 0.035;
    crane.lookAt(ahead);
    crane.rotateZ(bank);

    var amp = reduced ? 0.12 : (glide > 0 ? 0.18 : 0.72);
    if (bursting) {
      amp = 0.15 * (1 - u);
    }
    flapPhase += dt * (reduced ? 2 : 11);
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
  requestAnimationFrame(draw);
})();
