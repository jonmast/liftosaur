(function () {
  var g = globalThis;

  if (typeof g.console === "undefined") {
    g.console = {};
  }
  ["log", "warn", "error", "info", "debug"].forEach(function (level) {
    if (typeof g.console[level] !== "function") {
      g.console[level] = function () {
        var parts = [];
        for (var i = 0; i < arguments.length; i++) {
          var a = arguments[i];
          if (typeof a === "string") {
            parts.push(a);
          } else {
            try {
              parts.push(JSON.stringify(a));
            } catch (e) {
              parts.push(String(a));
            }
          }
        }
        __native_log(level, parts.join(" "));
      };
    }
  });

  if (typeof g.process === "undefined") {
    g.process = { env: {}, platform: "android", exit: function () {} };
  }

  if (typeof g.TextEncoder === "undefined") {
    g.TextEncoder = function TextEncoder() {
      this.encoding = "utf-8";
    };
    g.TextEncoder.prototype.encode = function (str) {
      str = str === undefined ? "" : String(str);
      var bytes = [];
      for (var i = 0; i < str.length; i++) {
        var c = str.charCodeAt(i);
        if (c < 0x80) {
          bytes.push(c);
        } else if (c < 0x800) {
          bytes.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
        } else if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
          var c2 = str.charCodeAt(i + 1);
          if (c2 >= 0xdc00 && c2 <= 0xdfff) {
            var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
            bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
            i++;
          } else {
            bytes.push(0xef, 0xbf, 0xbd);
          }
        } else {
          bytes.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
        }
      }
      return new Uint8Array(bytes);
    };
  }

  if (typeof g.TextDecoder === "undefined") {
    g.TextDecoder = function TextDecoder(encoding) {
      this.encoding = encoding || "utf-8";
    };
    g.TextDecoder.prototype.decode = function (input) {
      if (input == null) {
        return "";
      }
      var bytes = input instanceof Uint8Array ? input : new Uint8Array(input.buffer || input);
      var out = "";
      var i = 0;
      while (i < bytes.length) {
        var b = bytes[i++];
        var cp;
        if (b < 0x80) {
          cp = b;
        } else if (b >= 0xc0 && b < 0xe0) {
          cp = ((b & 0x1f) << 6) | (bytes[i++] & 0x3f);
        } else if (b >= 0xe0 && b < 0xf0) {
          cp = ((b & 0x0f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
        } else {
          cp = ((b & 0x07) << 18) | ((bytes[i++] & 0x3f) << 12) | ((bytes[i++] & 0x3f) << 6) | (bytes[i++] & 0x3f);
        }
        if (cp > 0xffff) {
          cp -= 0x10000;
          out += String.fromCharCode(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
        } else {
          out += String.fromCharCode(cp);
        }
      }
      return out;
    };
  }
})();
