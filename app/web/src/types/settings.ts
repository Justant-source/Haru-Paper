// 설정. 원본: docs/server/api.md "설정" 절.

export interface WeatherLocation {
  lat: number
  lon: number
  label: string
}

export interface SettingsResponse {
  weather: WeatherLocation
}
