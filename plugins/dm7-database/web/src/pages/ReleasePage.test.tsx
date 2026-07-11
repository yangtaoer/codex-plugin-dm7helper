import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import type { ApiClient, ReleaseSnapshot } from '../api/types'
import { ReleasePage } from './ReleasePage'

const snapshot=(version:string,count:number):ReleaseSnapshot=>({sessionShortId:'abc123',currentVersion:version,databaseFingerprint:'a'.repeat(64),bindingState:'MATCH',statementCount:count,excludedCount:1,failedCount:1,sqlPreview:count?'CREATE TABLE 中文表(ID INT);':'',previewTruncated:false,firstSequence:count?1:null,lastSequence:count?2:null,runningCount:0,entriesTruncated:false,entries:count?[{sequence:1,index:0,kind:'DDL',status:'SUCCEEDED',source:'CONSOLE',purpose:'MIGRATION',recorded:true,exclusionReason:null,createdAt:'2026-07-11T08:00:00Z',sqlSummary:'CREATE TABLE 中文表(ID INT)'}]:[],artifacts:count?[]:[{id:'export-1',state:'COMPLETE',version:'v001',filename:'dm7-abc-v001.sql',sha256:'b'.repeat(64),byteLength:128,statementCount:2,firstSequence:1,lastSequence:2,createdAt:'2026-07-11T08:01:00Z',completedAt:'2026-07-11T08:01:01Z',downloadAvailable:true,downloadUrl:'/api/release/artifacts/export-1/download',integrityState:'VERIFIED'}]})

it('confirms export then trusts authoritative v002 and supports download cleanup',async()=>{
 const release=vi.fn().mockResolvedValueOnce(snapshot('v001',2)).mockResolvedValue(snapshot('v002',0));const releaseExport=vi.fn().mockResolvedValue({id:'export-1'});const downloadArtifact=vi.fn().mockResolvedValue({filename:'dm7-abc-v001.sql',blob:new Blob(['sql'])});
 const create=vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:test'),revoke=vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{})
 const click=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{})
 render(<ReleasePage api={{release,releaseExport,downloadArtifact} as unknown as ApiClient}/>)
 expect(await screen.findByText('v001')).toBeTruthy();fireEvent.click(screen.getByRole('button',{name:'发版并导出'}));fireEvent.click(screen.getByLabelText('我已了解当前日志将轮转到下一版本'))
 fireEvent.click(screen.getByRole('button',{name:'确认发版'}));await waitFor(()=>expect(releaseExport).toHaveBeenCalledWith(true,expect.any(AbortSignal)))
 expect(await screen.findByText('v002')).toBeTruthy();expect(screen.getByText('当前版本暂无 SQL')).toBeTruthy()
 fireEvent.click(screen.getByRole('button',{name:/下载 dm7-abc-v001/}));await waitFor(()=>expect(downloadArtifact).toHaveBeenCalled());expect(create).toHaveBeenCalled();expect(revoke).toHaveBeenCalled();create.mockRestore();revoke.mockRestore();click.mockRestore()
})

it('disables export for empty or running logs and renders escaped truncated preview',async()=>{
 const unsafe={...snapshot('v001',2),runningCount:1,previewTruncated:true,sqlPreview:'<script>alert(1)</script>\n中文'}
 const {rerender}=render(<ReleasePage api={{release:vi.fn().mockResolvedValue(unsafe)} as unknown as ApiClient}/>);expect(await screen.findByText('<script>alert(1)</script>')).toBeTruthy();expect(document.querySelector('script')).toBeNull();expect(screen.getByText(/UTF-8 安全边界截断/)).toBeTruthy();expect(screen.getByRole('button',{name:'发版并导出'})).toHaveProperty('disabled',true)
 rerender(<ReleasePage api={{release:vi.fn().mockResolvedValue(snapshot('v002',0))} as unknown as ApiClient}/>);expect(await screen.findByText('当前版本暂无 SQL')).toBeTruthy();expect(screen.getByRole('button',{name:'发版并导出'})).toHaveProperty('disabled',true)
})

it('recovers only recoverable artifacts and refreshes authoritative state',async()=>{
 const artifact={...snapshot('v002',0).artifacts[0],state:'RECOVERY_REQUIRED' as const,version:'v001',filename:null,downloadAvailable:false,downloadUrl:null,integrityState:'RECOVERABLE'}
 const release=vi.fn().mockResolvedValue({...snapshot('v002',0),artifacts:[artifact]}),releaseRecover=vi.fn().mockResolvedValue({id:artifact.id})
 render(<ReleasePage api={{release,releaseRecover} as unknown as ApiClient}/>);await screen.findByText('待恢复的密封导出');fireEvent.click(screen.getByRole('button',{name:'恢复导出'}));fireEvent.click(screen.getByRole('button',{name:'确认恢复'}));await waitFor(()=>expect(releaseRecover).toHaveBeenCalledWith('v001',true,expect.any(AbortSignal)));expect(release).toHaveBeenCalledTimes(2)
})

it('does not invent a next version when export conflicts',async()=>{
 const release=vi.fn().mockResolvedValue(snapshot('v001',2)),releaseExport=vi.fn().mockRejectedValue(new Error('发版锁冲突。'));render(<ReleasePage api={{release,releaseExport} as unknown as ApiClient}/>);await screen.findByText('v001');fireEvent.click(screen.getByRole('button',{name:'发版并导出'}));fireEvent.click(screen.getByLabelText('我已了解当前日志将轮转到下一版本'));fireEvent.click(screen.getByRole('button',{name:'确认发版'}));expect(await screen.findByRole('alert')).toHaveProperty('textContent',expect.stringContaining('发版锁冲突'));expect(screen.queryByText('v002')).toBeNull();expect(release).toHaveBeenCalledTimes(1)
})

it('surfaces download failure without allocating an object URL',async()=>{const create=vi.spyOn(URL,'createObjectURL');render(<ReleasePage api={{release:vi.fn().mockResolvedValue(snapshot('v002',0)),downloadArtifact:vi.fn().mockRejectedValue(new Error('导出物已损坏。'))} as unknown as ApiClient}/>);await screen.findByText('dm7-abc-v001.sql');fireEvent.click(screen.getByRole('button',{name:/下载 dm7-abc-v001/}));expect(await screen.findByRole('alert')).toHaveProperty('textContent',expect.stringContaining('导出物已损坏'));expect(create).not.toHaveBeenCalled();create.mockRestore()})
