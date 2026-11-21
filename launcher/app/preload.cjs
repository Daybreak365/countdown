'use strict';
const {contextBridge,ipcRenderer}=require('electron');
const invoke=channel=>(...args)=>ipcRenderer.invoke(channel,...args);
const subscribe=channel=>fn=>{const handler=(_event,value)=>fn(value);ipcRenderer.on(channel,handler);return ()=>ipcRenderer.removeListener(channel,handler);};
contextBridge.exposeInMainWorld('launcher',{getState:invoke('state'),saveSettings:invoke('settings'),run:invoke('run'),logout:invoke('logout'),cancel:invoke('cancel'),copyLoginCode:invoke('auth-copy'),window:invoke('window'),avatar:invoke('avatar'),onProgress:subscribe('progress'),onState:subscribe('state'),onAuth:subscribe('auth')});
