let accessHandle = null;
let fileHandle = null;

async function openFile(name) {
  if (accessHandle) accessHandle.close();
  const root = await navigator.storage.getDirectory();
  fileHandle = await root.getFileHandle(name, {create:true});
  accessHandle = await fileHandle.createSyncAccessHandle();
  return accessHandle.getSize();
}

self.onmessage = async ({data}) => {
  const {id, command, name, offset, size, bytes} = data;
  try {
    let result = null;
    if (command === "open") result = await openFile(name);
    else if (command === "size") result = accessHandle.getSize();
    else if (command === "truncate") {
      accessHandle.truncate(size);
      accessHandle.flush();
      result = accessHandle.getSize();
    } else if (command === "write") {
      const view = new Uint8Array(bytes);
      result = accessHandle.write(view, {at:offset});
      if (result !== view.byteLength) throw new Error("Incomplete browser storage write");
    } else if (command === "flush") {
      accessHandle.flush();
      result = accessHandle.getSize();
    } else if (command === "close") {
      if (accessHandle) {
        accessHandle.flush();
        accessHandle.close();
        accessHandle = null;
      }
    } else throw new Error("Unknown storage command");
    self.postMessage({id, result});
  } catch (error) {
    self.postMessage({id, error:error?.message || String(error)});
  }
};
