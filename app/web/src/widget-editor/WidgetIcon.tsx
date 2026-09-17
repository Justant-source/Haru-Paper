// descriptor.icon(letter|chart|weather|calendar|text|image, 모르는 값이면 기본)을 아이콘으로 매핑.
// 위젯 종류를 하드코딩하지 않기 위해(작업지시서 07 7.1-3) type이 아니라 icon 문자열만 본다.
import type { ReactElement } from 'react'
import {
  IconSchedules,
  IconWidgetChart,
  IconWidgetGeneric,
  IconWidgetImage,
  IconWidgetLetter,
  IconWidgetText,
  IconWidgetWeather,
} from '../components/icons'

const ICONS: Record<string, (props: { className?: string }) => ReactElement> = {
  letter: IconWidgetLetter,
  chart: IconWidgetChart,
  weather: IconWidgetWeather,
  calendar: IconSchedules,
  text: IconWidgetText,
  image: IconWidgetImage,
}

export function WidgetIcon({ icon, className }: { icon: string | undefined; className?: string }) {
  const Component = (icon && ICONS[icon]) || IconWidgetGeneric
  return <Component className={className} />
}
