// 자리 표시용. 위젯 그리드 편집기로 교체된다(.temp/07-위젯그리드-작업지시서.md, 담당: A1).
import { useNavigate } from 'react-router-dom'
import { IconBack } from '../components/icons'

export function FormatEditPage() {
  const navigate = useNavigate()
  return (
    <div className="format-edit-page">
      <header className="page-header">
        <button type="button" className="icon-btn" onClick={() => navigate('/')} aria-label="뒤로">
          <IconBack />
        </button>
      </header>
      <div className="page">준비 중</div>
    </div>
  )
}
